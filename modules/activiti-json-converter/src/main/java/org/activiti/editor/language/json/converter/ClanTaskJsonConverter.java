
package org.activiti.editor.language.json.converter;

import java.util.Map;

import org.activiti.bpmn.model.BaseElement;
import org.activiti.bpmn.model.FieldExtension;
import org.activiti.bpmn.model.FlowElement;
import org.activiti.bpmn.model.ImplementationType;
import org.activiti.bpmn.model.ServiceTask;
import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class ClanTaskJsonConverter extends BaseBpmnJsonConverter {

	public static void fillBpmnTypes(Map<Class<? extends BaseElement>, Class<? extends BaseBpmnJsonConverter>> convertersToJsonMap) {}

	public static void fillJsonTypes(Map<String, Class<? extends BaseBpmnJsonConverter>> convertersToBpmnMap) {
		convertersToBpmnMap.put("ClanTask", ClanTaskJsonConverter.class); //$NON-NLS-1$
	}

	public static void fillTypes(Map<String, Class<? extends BaseBpmnJsonConverter>> convertersToBpmnMap, Map<Class<? extends BaseElement>, Class<? extends BaseBpmnJsonConverter>> convertersToJsonMap) {
		fillJsonTypes(convertersToBpmnMap);
		fillBpmnTypes(convertersToJsonMap);
	}

	@Override
	protected void convertElementToJson(ObjectNode propertiesNode, BaseElement baseElement) {
		// done in service task
	}

	@Override
	protected FlowElement convertJsonToElement(JsonNode elementNode, JsonNode modelNode, Map<String, JsonNode> shapeMap) {
		FieldExtension field;
		ServiceTask task = new ServiceTask();
		task.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_CLASS);
		task.setImplementation("ch.claninfo.activiti.BoMethod"); //$NON-NLS-1$
		String value = getPropertyValueAsString("clantaskmethod", elementNode); //$NON-NLS-1$
		if (StringUtils.isNotEmpty(value)) {
			int pos1 = value.indexOf('.');
			int pos2 = value.indexOf('/');
			if (pos1 > 0 && pos2 > pos1) {
				field = new FieldExtension();
				field.setFieldName("modul"); //$NON-NLS-1$
				field.setStringValue(value.substring(0, pos1));
				task.getFieldExtensions().add(field);
				field = new FieldExtension();
				field.setFieldName("bo"); //$NON-NLS-1$
				field.setStringValue(value.substring(pos1 + 1, pos2));
				task.getFieldExtensions().add(field);
				field = new FieldExtension();
				field.setFieldName("method"); //$NON-NLS-1$
				field.setStringValue(value.substring(pos2 + 1));
				task.getFieldExtensions().add(field);
			}
		}
		value = getPropertyValueAsString("clanlsname", elementNode); //$NON-NLS-1$
		if (StringUtils.isNotEmpty(value)) {
			field = new FieldExtension();
			field.setFieldName("lsName"); //$NON-NLS-1$
			field.setStringValue(value);
			task.getFieldExtensions().add(field);
		}

		value = getPropertyValueAsString("clanxoname", elementNode); //$NON-NLS-1$
		if (StringUtils.isNotEmpty(value)) {
			field = new FieldExtension();
			field.setFieldName("xoReport"); //$NON-NLS-1$
			field.setStringValue(value);
			task.getFieldExtensions().add(field);
		}

		value = getPropertyValueAsString("clancommitbefore", elementNode); //$NON-NLS-1$
		if (StringUtils.isNotEmpty(value)) {
			field = new FieldExtension();
			field.setFieldName("commitBefore"); //$NON-NLS-1$
			field.setStringValue(value);
			task.getFieldExtensions().add(field);
		}

		JsonNode fieldsNode = getProperty("servicetaskfields", elementNode); //$NON-NLS-1$
		if (fieldsNode != null) {
			JsonNode itemsArrayNode = fieldsNode.get("fields"); //$NON-NLS-1$
			if (itemsArrayNode != null) {
				StringBuffer sval = new StringBuffer();
				for (JsonNode itemNode : itemsArrayNode) {
					sval.append(getValueAsString(PROPERTY_SERVICETASK_FIELD_NAME, itemNode));
					sval.append('=');
					String val = getValueAsString(PROPERTY_SERVICETASK_FIELD_STRING_VALUE, itemNode);
					if (StringUtils.isEmpty(val)) {
						val = getValueAsString(PROPERTY_SERVICETASK_FIELD_EXPRESSION, itemNode);
						if (StringUtils.isEmpty(val)) {
							val = getValueAsString(PROPERTY_SERVICETASK_FIELD_STRING, itemNode);
							if (StringUtils.isEmpty(val)) {
								val = "${null}"; //$NON-NLS-1$
							}
						}
					}
					sval.append(val);
					sval.append(';');
				}
				field = new FieldExtension();
				field.setFieldName("inParamMapping"); //$NON-NLS-1$
				field.setStringValue(sval.toString());
				task.getFieldExtensions().add(field);
			}
		}

		value = getPropertyValueAsString(PROPERTY_SERVICETASK_RESULT_VARIABLE, elementNode);
		if (StringUtils.isNotEmpty(value)) {
			field = new FieldExtension();
			field.setFieldName("outParamNames"); //$NON-NLS-1$
			field.setStringValue(value);
			task.getFieldExtensions().add(field);
		}

		return task;
	}

	@Override
	protected String getStencilId(BaseElement baseElement) {
		return "ClanTask"; //$NON-NLS-1$
	}
}
