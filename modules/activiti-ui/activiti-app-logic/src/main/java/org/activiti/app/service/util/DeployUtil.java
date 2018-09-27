
package org.activiti.app.service.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.activiti.app.domain.editor.AbstractModel;
import org.activiti.app.domain.editor.AppDefinition;
import org.activiti.app.domain.editor.AppModelDefinition;
import org.activiti.app.domain.editor.Model;
import org.activiti.app.security.SecurityUtils;
import org.activiti.app.service.api.ModelService;
import org.activiti.bpmn.model.BpmnModel;
import org.activiti.bpmn.model.FlowElement;
import org.activiti.bpmn.model.Process;
import org.activiti.bpmn.model.StartEvent;
import org.activiti.bpmn.model.SubProcess;
import org.activiti.bpmn.model.UserTask;
import org.activiti.dmn.model.DmnDefinition;
import org.activiti.dmn.xml.converter.DmnXMLConverter;
import org.activiti.editor.dmn.converter.DmnJsonConverter;
import org.activiti.editor.language.json.converter.util.CollectionUtils;
import org.activiti.engine.ActivitiException;
import org.activiti.engine.RepositoryService;
import org.activiti.engine.repository.Deployment;
import org.activiti.engine.repository.DeploymentBuilder;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Deployment of one or all aApp definitions.
 */
public class DeployUtil {

	private static final Logger LOGGER = LoggerFactory.getLogger(DeployUtil.class);
	/** the JSON converter instance */
	public static final DmnJsonConverter DMN_JSON_CONVERTER = new DmnJsonConverter();
	/** the XML converter instance */
	public static final DmnXMLConverter DMN_XML_CONVERTER = new DmnXMLConverter();

	/**
	 * deploy one application
	 * 
	 * @param appDefinitionModel the application
	 * @param modelService source
	 * @param repositoryService destination
	 * @param objectMapper json parser
	 * @return the dployment
	 * @throws IOException Parse error
	 */
	public static Deployment deploy(Model appDefinitionModel, ModelService modelService, RepositoryService repositoryService, ObjectMapper objectMapper) throws IOException {
		LOGGER.info("Deploying " + appDefinitionModel.getKey()); //$NON-NLS-1$
		AppDefinition appDefinition = objectMapper.readValue(appDefinitionModel.getModelEditorJson(), AppDefinition.class);
		if (CollectionUtils.isNotEmpty(appDefinition.getModels())) {
			DeploymentBuilder deploymentBuilder = repositoryService.createDeployment().name(appDefinitionModel.getName()).key(appDefinitionModel.getKey());
			Map<String, Model> formMap = new HashMap<String, Model>();
			Map<String, Model> decisionTableMap = new HashMap<String, Model>();
			for (AppModelDefinition appModelDef : appDefinition.getModels()) {
				LOGGER.debug("  contains workflow " + appModelDef.getId()); //$NON-NLS-1$
				AbstractModel processModel = modelService.getModel(appModelDef.getId());
				if (processModel == null) {
					throw new ActivitiException("Model " + appModelDef.getId() + " for app definition " + appDefinitionModel.getId() + " could not be found."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				}

				List<Model> referencedModels = modelService.getReferencedModels(processModel.getId());
				for (Model childModel : referencedModels) {
					if (AbstractModel.MODEL_TYPE_FORM == childModel.getModelType().intValue()) {
						formMap.put(childModel.getId(), childModel);
					} else if (AbstractModel.MODEL_TYPE_DECISION_TABLE == childModel.getModelType().intValue()) {
						decisionTableMap.put(childModel.getId(), childModel);
					}
				}

				BpmnModel bpmnModel = modelService.getBpmnModel(processModel, formMap, decisionTableMap);
				Map<String, StartEvent> startEventMap = processNoneStartEvents(bpmnModel);

				for (Process process : bpmnModel.getProcesses()) {
					processUserTasks(process.getFlowElements(), process, startEventMap);
				}

				byte[] modelXML = modelService.getBpmnXML(bpmnModel);
				deploymentBuilder.addInputStream(processModel.getKey().replaceAll(" ", "") + ".bpmn", new ByteArrayInputStream(modelXML)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}

			if (formMap.size() > 0) {
				for (String formId : formMap.keySet()) {
					LOGGER.debug("    references form " + formId); //$NON-NLS-1$
					Model formInfo = formMap.get(formId);
					deploymentBuilder.addString("form-" + formInfo.getKey() + ".form", formInfo.getModelEditorJson()); //$NON-NLS-1$ //$NON-NLS-2$
				}
			}

			if (decisionTableMap.size() > 0) {
				for (String decisionTableId : decisionTableMap.keySet()) {
					LOGGER.debug("    references decision table " + decisionTableId); //$NON-NLS-1$
					Model decisionTableInfo = decisionTableMap.get(decisionTableId);
					JsonNode decisionTableNode = objectMapper.readTree(decisionTableInfo.getModelEditorJson());
					DmnDefinition dmnDefinition = DMN_JSON_CONVERTER.convertToDmn(decisionTableNode, decisionTableInfo.getId(), decisionTableInfo.getVersion(), decisionTableInfo.getLastUpdated());
					byte[] dmnXMLBytes = DMN_XML_CONVERTER.convertToXML(dmnDefinition);
					deploymentBuilder.addBytes("dmn-" + decisionTableInfo.getKey() + ".dmn", dmnXMLBytes); //$NON-NLS-1$ //$NON-NLS-2$
				}
			}
			
			deploymentBuilder.addString("translations.json", gatherTexts(appDefinitionModel, modelService, objectMapper).getModelEditorJson()); //$NON-NLS-1$
			return deploymentBuilder.deploy();
		}
		return null;
	}

	/**
	 * deploy all applications contained in the repository
	 * 
	 * @param modelService source
	 * @param repositoryService destination
	 * @param objectMapper json parser
	 */
	public static void deployAll(ModelService modelService, RepositoryService repositoryService, ObjectMapper objectMapper) {
		for (Model appDefinitionModel : modelService.getModelsByModelType(Integer.valueOf(AbstractModel.MODEL_TYPE_APP))) {
			try {
				deploy(appDefinitionModel, modelService, repositoryService, objectMapper);
			}
			catch (Throwable e) {
				LOGGER.warn("Deployment of " + appDefinitionModel.getKey() + " failed.", e); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
	}

	/**
	 * gather the texts of one application
	 * 
	 * @param appDefinitionModel the application
	 * @param modelService source
	 * @param objectMapper json parser
	 * @return the dployment
	 * @throws IOException Parse error
	 */
	public static Model gatherTexts(Model appDefinitionModel, ModelService modelService, ObjectMapper objectMapper) throws IOException {
		AppDefinition appDefinition = objectMapper.readValue(appDefinitionModel.getModelEditorJson(), AppDefinition.class);
		if (CollectionUtils.isNotEmpty(appDefinition.getModels())) {
			Model translationModel = null;
			ObjectNode texts;
			List<Model> models = modelService.getModelsByModelType(Integer.valueOf(AbstractModel.MODEL_TYPE_TRANSLATION), appDefinitionModel.getKey());
			for(Model m : models) {
				if(m.getKey().equals(appDefinitionModel.getKey())) {
					translationModel = m;
					break;
				}
			}
			boolean changed = translationModel == null;
			if (changed) {
				texts = objectMapper.createObjectNode();
			} else {
				texts = (ObjectNode) objectMapper.readTree(translationModel.getModelEditorJson());
			}
			
			for (AppModelDefinition appModelDef : appDefinition.getModels()) {
				AbstractModel processModel = modelService.getModel(appModelDef.getId());
				if (processModel == null) {
					throw new ActivitiException("Model " + appModelDef.getId() + " for app definition " + appDefinitionModel.getId() + " could not be found."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				}
				JsonNode jsonObject = objectMapper.readTree(processModel.getModelEditorJson());
				changed = gatherBpmnDiagramTexts(processModel.getKey(), jsonObject, texts,objectMapper) ? true : changed;
				List<Model> referencedModels = modelService.getReferencedModels(processModel.getId());
				for (Model childModel : referencedModels) {
					if (AbstractModel.MODEL_TYPE_FORM == childModel.getModelType().intValue()) {
						jsonObject = objectMapper.readTree(childModel.getModelEditorJson());
						changed = gatherFormTexts(childModel.getKey(), jsonObject, texts,objectMapper) ? true : changed;
					}
				}
			}

			if (translationModel == null) {
				translationModel = new Model();
				translationModel.setModelType(Integer.valueOf(AbstractModel.MODEL_TYPE_TRANSLATION));
				translationModel.setKey(appDefinitionModel.getKey());
				translationModel.setName(appDefinitionModel.getName());
				translationModel.setComment("Translations for " + appDefinitionModel.getName()); //$NON-NLS-1$
				translationModel.setModelEditorJson(objectMapper.writeValueAsString(texts));
				return modelService.createModel(translationModel, SecurityUtils.getCurrentUserObject());
			}
			if(changed) {
				return modelService.saveModel(translationModel, objectMapper.writeValueAsString(texts), null, true, "Texts verification", SecurityUtils.getCurrentUserObject()); //$NON-NLS-1$
			}
			return translationModel;
		}
		return null;
	}

	static boolean addText(String key, ObjectNode texts, String text) {
		JsonNode lText = texts.get(key);
		if(text == null) {
			if(lText != null) {
				texts.remove(key);
				return true;
			}
		} else if(lText == null || !text.equals(lText.asText())) {
			texts.put(key, text);
			return true;
		}
		return false;
	}

	
	static boolean addTexts(String prefix, JsonNode properties, ObjectNode texts, ObjectMapper objectMapper) {
		boolean changed = false;
		JsonNode node = texts.get(prefix);
		String name = getNodeText(properties, "name"); //$NON-NLS-1$
		String documentation = getNodeText(properties, "documentation"); //$NON-NLS-1$
		if (name == null && documentation == null) {
			if( node != null ) {
				texts.remove(prefix);
				changed = true;
			}
		} else {
			ObjectNode text;
			if(node == null || !node.isObject()) {
				text = objectMapper.createObjectNode();
				texts.set(prefix, text);
				changed = true;
			} else {
				text = (ObjectNode) node;
			}
			changed = addText("default",text,name) ? true : changed; //$NON-NLS-1$
			changed = addText("help.default",text,documentation) ? true : changed; //$NON-NLS-1$
		}
		return changed;
	}

	static boolean gatherBpmnDiagramTexts(String pId, JsonNode jsonObject, ObjectNode texts, ObjectMapper objectMapper) {
		JsonNode properties = jsonObject.get("properties"); //$NON-NLS-1$
		boolean changed = addTexts(pId, properties, texts,objectMapper);
		JsonNode childShapes = jsonObject.get("childShapes"); //$NON-NLS-1$
		if (childShapes != null && childShapes.isArray()) {
			for (int i = 0; i < childShapes.size(); i++) {
				changed = gatherChildShapeTexts(pId, childShapes.get(i), texts,objectMapper) ? true :  changed;
			}
		}
		return changed;
	}

	static boolean gatherChildShapeTexts(String path, JsonNode childShape, ObjectNode texts, ObjectMapper objectMapper) {
		boolean changed = false;
		JsonNode properties = childShape.get("properties"); //$NON-NLS-1$
		String id = getNodeText(properties, "overrideid"); //$NON-NLS-1$
		if (id == null) {
			id = getNodeText(childShape, "resourceId"); //$NON-NLS-1$
		}
		id = path + '.' + id;
		changed = addTexts(id, properties, texts,objectMapper) ? true :  changed;
		JsonNode childShapes = childShape.get("childShapes"); //$NON-NLS-1$
		if (childShapes != null && childShapes.isArray()) {
			for (int i = 0; i < childShapes.size(); i++) {
				changed = gatherChildShapeTexts(id, childShapes.get(i), texts,objectMapper) ? true : changed;
			}
		}
		return changed;
	}

	static boolean gatherFormTexts(String pId, JsonNode jsonObject, ObjectNode texts, ObjectMapper objectMapper) {
		boolean changed = addTexts(pId, jsonObject, texts,objectMapper);
		JsonNode fields = jsonObject.get("fields"); //$NON-NLS-1$
		if (fields != null && fields.isArray()) {
			for (int i = 0; i < fields.size(); i++) {
				JsonNode field = fields.get(i);
				changed = gatherFormTexts(pId + '.' + getNodeText(field, "id"), field, texts,objectMapper) ? true : changed; //$NON-NLS-1$
			}
		}

		JsonNode options = jsonObject.get("options"); //$NON-NLS-1$
		if (options != null && options.isArray()) {
			for (int i = 0; i < options.size(); i++) {
				JsonNode option = options.get(i);
				String id = getNodeText(option, "id"); //$NON-NLS-1$
				if (id == null) {
					id = "option_" + i; //$NON-NLS-1$
				}
				changed = gatherFormTexts(pId + '.' + id, option, texts,objectMapper) ? true : changed;
			}
		}
		return changed;
	}

	static String getNodeText(JsonNode jsonObject, String name) {
		if (jsonObject == null) {
			return null;
		}
		JsonNode node = jsonObject.get(name);
		if (node == null || node.isNull()) {
			return null;
		}
		String text = node.asText();
		if (text.isEmpty()) {
			return null;
		}
		return text;
	}

	static Map<String, StartEvent> processNoneStartEvents(BpmnModel bpmnModel) {
		Map<String, StartEvent> startEventMap = new HashMap<String, StartEvent>();
		for (Process process : bpmnModel.getProcesses()) {
			for (FlowElement flowElement : process.getFlowElements()) {
				if (flowElement instanceof StartEvent) {
					StartEvent startEvent = (StartEvent) flowElement;
					if (CollectionUtils.isEmpty(startEvent.getEventDefinitions())) {
						if (StringUtils.isEmpty(startEvent.getInitiator())) {
							startEvent.setInitiator("initiator"); //$NON-NLS-1$
						}
						startEventMap.put(process.getId(), startEvent);
						break;
					}
				}
			}
		}
		return startEventMap;
	}

	static void processUserTasks(Collection<FlowElement> flowElements, Process process, Map<String, StartEvent> startEventMap) {
		for (FlowElement flowElement : flowElements) {
			if (flowElement instanceof UserTask) {
				UserTask userTask = (UserTask) flowElement;
				if ("$INITIATOR".equals(userTask.getAssignee())) { //$NON-NLS-1$
					if (startEventMap.get(process.getId()) != null) {
						userTask.setAssignee("${" + startEventMap.get(process.getId()).getInitiator() + "}"); //$NON-NLS-1$ //$NON-NLS-2$
					}
				}

			} else if (flowElement instanceof SubProcess) {
				processUserTasks(((SubProcess) flowElement).getFlowElements(), process, startEventMap);
			}
		}
	}
}
