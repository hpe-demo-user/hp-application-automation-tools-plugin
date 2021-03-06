package com.hp.octane.plugins.jenkins.tests;

import com.google.inject.Inject;
import com.hp.mqm.client.MqmRestClient;
import com.hp.mqm.client.exception.SharedSpaceNotExistException;
import com.hp.mqm.client.exception.FileNotFoundException;
import com.hp.mqm.client.exception.LoginException;
import com.hp.mqm.client.exception.RequestException;
import com.hp.mqm.client.exception.TemporarilyUnavailableException;
import com.hp.octane.plugins.jenkins.client.EventPublisher;
import com.hp.octane.plugins.jenkins.client.JenkinsInsightEventPublisher;
import com.hp.octane.plugins.jenkins.client.JenkinsMqmRestClientFactory;
import com.hp.octane.plugins.jenkins.client.JenkinsMqmRestClientFactoryImpl;
import com.hp.octane.plugins.jenkins.client.RetryModel;
import com.hp.octane.plugins.jenkins.configuration.ConfigurationService;
import com.hp.octane.plugins.jenkins.configuration.ServerConfiguration;
import com.hp.octane.plugins.jenkins.identity.ServerIdentity;
import hudson.Extension;
import hudson.FilePath;
import hudson.matrix.MatrixRun;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.TaskListener;
import hudson.util.TimeUnit2;
import jenkins.YesNoMaybe;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateFormatUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.util.Date;

@Extension(dynamicLoadable = YesNoMaybe.NO)
public class TestDispatcher extends SafeLoggingAsyncPeriodWork {
	private static Logger logger = LogManager.getLogger(TestDispatcher.class);

	static final String TEST_AUDIT_FILE = "mqmTests_audit.json";

	@Inject
	private RetryModel retryModel;

	private TestResultQueue queue;

	private JenkinsMqmRestClientFactory clientFactory;

	private EventPublisher eventPublisher;

	public TestDispatcher() {
		super("MQM Test Dispatcher");
	}

	@Override
	protected void doExecute(TaskListener listener) throws IOException, InterruptedException {
		if (queue.peekFirst() == null) {
			return;
		}
		if (retryModel.isQuietPeriod()) {
			logger.info("There are pending test results, but we are in quiet period");
			return;
		}
		MqmRestClient client = null;
		ServerConfiguration configuration = null;
		TestResultQueue.QueueItem item;
		while ((item = queue.peekFirst()) != null) {
			if (client == null) {
				configuration = ConfigurationService.getServerConfiguration();
				if (StringUtils.isEmpty(configuration.location)) {
					logger.warn("There are pending test results, but MQM server location is not specified, results can't be submitted");
					return;
				}
				if (eventPublisher.isSuspended()) {
					logger.warn("There are pending test results, but event dispatching is suspended");
					return;
				}
				logger.info("There are pending test results, connecting to the MQM server");
				client = clientFactory.obtain(
						configuration.location,
						configuration.sharedSpace,
						configuration.username,
						configuration.password);
				try {
					client.validateConfigurationWithoutLogin();
				} catch (SharedSpaceNotExistException e) {
					logger.warn("Invalid shared space. Pending test results can't be submitted", e);
					retryModel.failure();
					return;
				} catch (LoginException e) {
					logger.warn("Login failed, pending test results can't be submitted", e);
					retryModel.failure();
					return;
				} catch (RequestException e) {
					logger.warn("Problem with communication with MQM server. Pending test results can't be submitted", e);
					retryModel.failure();
					return;
				}

				retryModel.success();
			}

			AbstractProject project = (AbstractProject) Jenkins.getInstance().getItemByFullName(item.projectName);
			if (project == null) {
				logger.warn("Project [" + item.projectName + "] no longer exists, pending test results can't be submitted");
				queue.remove();
				continue;
			}

			AbstractBuild build = project.getBuildByNumber(item.buildNumber);
			if (build == null) {
				logger.warn("Build [" + item.projectName + "#" + item.buildNumber + "] no longer exists, pending test results can't be submitted");
				queue.remove();
				continue;
			}

			String jobName;
			if (build instanceof MatrixRun) {
				jobName = ((MatrixRun) build).getProject().getParent().getName();
			} else {
				jobName = build.getProject().getName();
			}

			Boolean needTestResult = client.isTestResultRelevant(ServerIdentity.getIdentity(), jobName);

			if (needTestResult) {
				try {
					Long id = null;
					try {
						File resultFile = new File(build.getRootDir(), TestListener.TEST_RESULT_FILE);
						id = client.postTestResult(resultFile, false);
					} catch (TemporarilyUnavailableException e) {
						logger.warn("Server temporarily unavailable, will try later", e);
						audit(configuration, build, null, true);
						break;
					} catch (RequestException e) {
						logger.warn("Failed to submit test results [" + build.getProject().getName() + "#" + build.getNumber() + "]", e);
					}

					if (id != null) {
						logger.info("Successfully pushed test results of build [" + item.projectName + "#" + item.buildNumber + "]");
						queue.remove();
					} else {
						logger.warn("Failed to push test results of build [" + item.projectName + "#" + item.buildNumber + "]");
						if (!queue.failed()) {
							logger.warn("Maximum number of attempts reached, operation will not be re-attempted for this build");
						}
						client = null;
					}
					audit(configuration, build, id, false);
				} catch (FileNotFoundException e) {
					logger.warn("File no longer exists, failed to push test results of build [" + item.projectName + "#" + item.buildNumber + "]");
					queue.remove();
				}
			} else {
				logger.info("Test result not needed for build [" + item.projectName + "#" + item.buildNumber + "]");
				queue.remove();
			}
		}
	}

	private void audit(ServerConfiguration configuration, AbstractBuild build, Long id, boolean temporarilyUnavailable) throws IOException, InterruptedException {
		FilePath auditFile = new FilePath(new File(build.getRootDir(), TEST_AUDIT_FILE));
		JSONArray audit;
		if (auditFile.exists()) {
			InputStream is = auditFile.read();
			audit = JSONArray.fromObject(IOUtils.toString(is, "UTF-8"));
			IOUtils.closeQuietly(is);
		} else {
			audit = new JSONArray();
		}
		JSONObject event = new JSONObject();
		event.put("id", id);
		event.put("pushed", id != null);
		event.put("date", DateFormatUtils.ISO_DATETIME_TIME_ZONE_FORMAT.format(new Date()));
		event.put("location", configuration.location);
		event.put("sharedSpace", configuration.sharedSpace);
		if (temporarilyUnavailable) {
			event.put("temporarilyUnavailable", true);
		}
		audit.add(event);
		auditFile.write(audit.toString(), "UTF-8");
	}

	@Override
	public long getRecurrencePeriod() {
		String value = System.getProperty("MQM.TestDispatcher.Period");
		if (!StringUtils.isEmpty(value)) {
			return Long.valueOf(value);
		}
		return TimeUnit2.SECONDS.toMillis(10);
	}

	@Inject
	public void setMqmRestClientFactory(JenkinsMqmRestClientFactoryImpl clientFactory) {
		this.clientFactory = clientFactory;
	}

	@Inject
	public void setTestResultQueue(TestResultQueueImpl queue) {
		this.queue = queue;
	}

	@Inject
	public void setEventPublisher(JenkinsInsightEventPublisher eventPublisher) {
		this.eventPublisher = eventPublisher;
	}


	void _setMqmRestClientFactory(JenkinsMqmRestClientFactory clientFactory) {
		this.clientFactory = clientFactory;
	}


	void _setTestResultQueue(TestResultQueue queue) {
		this.queue = queue;
	}


	void _setRetryModel(RetryModel retryModel) {
		this.retryModel = retryModel;
	}


	void _setEventPublisher(EventPublisher eventPublisher) {
		this.eventPublisher = eventPublisher;
	}
}
