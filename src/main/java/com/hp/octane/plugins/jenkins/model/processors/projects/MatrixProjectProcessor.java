package com.hp.octane.plugins.jenkins.model.processors.projects;

import hudson.matrix.MatrixProject;
import hudson.model.Job;
import hudson.tasks.Builder;

import java.util.List;

/**
 * Created with IntelliJ IDEA.
 * User: gullery
 * Date: 24/12/14
 * Time: 13:30
 * To change this template use File | Settings | File Templates.
 */

class MatrixProjectProcessor extends AbstractProjectProcessor<MatrixProject> {

	MatrixProjectProcessor(Job project) {
		super((MatrixProject) project);

		//  Internal phases
		//
		super.processBuilders(this.job.getBuilders(), this.job);

		//  Post build phases
		//
		super.processPublishers(this.job);
	}

	@Override
	public List<Builder> tryGetBuilders() {
		return job.getBuilders();
	}

	@Override
	public void scheduleBuild(String parametersBody) {
		throw new RuntimeException("non yet implemented");
	}
}
