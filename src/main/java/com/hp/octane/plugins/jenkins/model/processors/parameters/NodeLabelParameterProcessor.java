package com.hp.octane.plugins.jenkins.model.processors.parameters;

import com.hp.octane.integrations.dto.parameters.CIParameter;
import com.hp.octane.integrations.dto.parameters.CIParameterType;
import com.hp.octane.plugins.jenkins.model.ModelFactory;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import org.jvnet.jenkins.plugins.nodelabelparameter.LabelParameterDefinition;
import org.jvnet.jenkins.plugins.nodelabelparameter.NodeParameterDefinition;

import java.util.ArrayList;

/**
 * Created by gullery on 19/02/2015.
 */

public class NodeLabelParameterProcessor extends AbstractParametersProcessor {
	NodeLabelParameterProcessor() {
	}

	@Override
	public CIParameter createParameterConfig(ParameterDefinition pd) {
		if (pd instanceof NodeParameterDefinition) {
			NodeParameterDefinition nodePd = (NodeParameterDefinition) pd;
			return ModelFactory.createParameterConfig(pd, CIParameterType.STRING, new ArrayList<Object>(nodePd.allowedSlaves));
		} else if (pd instanceof LabelParameterDefinition) {
			LabelParameterDefinition labelPd = (LabelParameterDefinition) pd;
			return ModelFactory.createParameterConfig(pd, CIParameterType.STRING);
		} else {
			return ModelFactory.createParameterConfig(pd);
		}
	}

	@Override
	public CIParameter createParameterInstance(ParameterDefinition pd, ParameterValue pv) {
		return ModelFactory.createParameterInstance(createParameterConfig(pd), pv);
	}
}
