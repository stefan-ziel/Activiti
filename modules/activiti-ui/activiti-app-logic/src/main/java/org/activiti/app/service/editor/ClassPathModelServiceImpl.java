/* $Id$ */

package org.activiti.app.service.editor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.activiti.app.domain.editor.AbstractModel;
import org.activiti.app.domain.editor.Model;
import org.activiti.app.service.exception.InternalServerErrorException;
import org.activiti.engine.identity.User;
import org.apache.commons.codec.binary.Base64;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Configurable;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * scan the classpath for models
 */
@Configurable
public class ClassPathModelServiceImpl extends InMemoryModelServiceImpl {

	private static final String[] MODEL_TYPE_DIR = {"bpmn", "template", "form", "app", "decisiontable"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
	private static final String EXTENSION = ".json"; //$NON-NLS-1$
	private static final Logger LOGGER = Logger.getLogger(FileSystemModelServiceImpl.class);
	boolean initialized;
	String basePath;
	String encoding;
	
	/**
	 * @param pBasePath path to the model files
	 */
	public ClassPathModelServiceImpl(String pBasePath) {
		this(pBasePath, "UTF-8"); //$NON-NLS-1$
	}

	@Override
	public void deleteModel(String pModelId, boolean pCascadeHistory, boolean pDeleteRuntimeApp, String pComment, User pDeletedBy) {
		throw new InternalServerErrorException("This is a read only service"); //$NON-NLS-1$
	}

	@Override
	public Model getModel(String pModelId) {
		if(!initialized){
			init();
			initialized = true;
		}
		return super.getModel(pModelId);
	}

	@Override
	public Model saveModel(Model pModelObject) {
		throw new InternalServerErrorException("This is a read only service"); //$NON-NLS-1$
	}

	@Override
	protected Iterable<Model> allModels() {
		if(!initialized){
			init();
			initialized = true;
		}
		return super.allModels();
	}

	/**
	 * @param pBasePath path to the model files
	 * @param pEncoding encoding of the model files
	 */
	public ClassPathModelServiceImpl(String pBasePath, String pEncoding) {
		basePath = pBasePath;
		encoding = pEncoding;
		initialized = false;
	}
	
	private void init() {
		// register dynamic filters
		if (getClass().getClassLoader() instanceof URLClassLoader) {
			URLClassLoader ucl = (URLClassLoader) getClass().getClassLoader();
			URL[] urls = ucl.getURLs();
			for (int i = 0; i < urls.length; i++) {
				try {
					File file = new File(urls[i].toURI());
					if (file.exists()) {
						if (file.isDirectory()) {
							file = new File(file, basePath);
							if (file.exists() && file.isDirectory()) {
								for (String dirName : MODEL_TYPE_DIR) {
									File dir = new File(file, dirName);
									if (dir.exists() && dir.isDirectory()) {
										for (File child : dir.listFiles()) {
											String name = child.getName();
											if (child.isFile() && name.endsWith(EXTENSION)) {
												loadModel(child.getAbsolutePath().replace('\\','/'), new FileInputStream(child), encoding);
											}
										}
									}
								}
							}
						} else {
							ZipFile zf = new ZipFile(file);
							try {
								for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements();) {
									ZipEntry ze = e.nextElement();
									String name = ze.getName();
									if (name.endsWith(EXTENSION) && name.startsWith(basePath)) {
										loadModel(name, zf.getInputStream(ze), encoding);
									}
								}
							}
							finally {
								zf.close();
							}
						}
					}
				}
				catch (URISyntaxException | IOException e) {
					LOGGER.warn("Error reading models from classpath", e); //$NON-NLS-1$
				}
			}
		}
	}

	void loadModel(String pName, InputStream pIn, String pEncoding) throws IOException {
		InputStreamReader in = new InputStreamReader(pIn, pEncoding);
		try {
			ObjectNode modelNode = (ObjectNode) getObjectMapper().readTree(in);
			Model newModel = new Model();
			String id = getId(pName);
			newModel.setId(id);
			newModel.setKey(getModelKey(id));
			newModel.setModelType(getModelType(id));
			newModel.setVersion(getIntValue(modelNode, "version")); //$NON-NLS-1$
			newModel.setLastUpdatedBy(getTextValue(modelNode, "lastUpdatedBy")); //$NON-NLS-1$
			newModel.setLastUpdated(getDateValue(modelNode, "lastUpdated")); //$NON-NLS-1$
			newModel.setName(getTextValue(modelNode, "name")); //$NON-NLS-1$
			newModel.setDescription(getTextValue(modelNode, "description")); //$NON-NLS-1$
			newModel.setModelEditorJson(getObjectMapper().writeValueAsString(modelNode.get("modelEditorJson"))); //$NON-NLS-1$
			String thumbnail = getTextValue(modelNode, "thumbnail"); //$NON-NLS-1$
			if (thumbnail != null) {
				newModel.setThumbnail(Base64.decodeBase64(thumbnail));
			}
			newModel.setCreatedBy(getTextValue(modelNode, "createdBy")); //$NON-NLS-1$
			newModel.setCreated(getDateValue(modelNode, "created")); //$NON-NLS-1$
			models.put(newModel.getId(), newModel);
		}
		finally {
			in.close();
		}
	}

	String getId(String pName) {
		int index = pName.lastIndexOf('/');
		String name = pName.substring(index + 1);
		String type = pName.substring(0, index);
		index = type.lastIndexOf('/');
		type = type.substring(index + 1);
		return new StringBuilder().append('{').append(type).append('-').append(name.substring(0, name.length() - EXTENSION.length())).append('}').toString();
	}

	/**
	 * extract the model key from its id
	 * 
	 * @param pModelId model id
	 * @return the key
	 */
	protected String getModelKey(String pModelId) {
		if (pModelId == null) {
			return null;
		}
		int pos = pModelId.indexOf('-');
		return pModelId.substring(pos + 1, pModelId.length() - 1);
	}

	/**
	 * extract the model type from its id
	 * 
	 * @param pModelId model id
	 * @return the model type
	 */
	@Override
	public Integer getModelType(String pModelId) {
		if (pModelId != null) {
			int pos = pModelId.indexOf('-');
			String type = pModelId.substring(1, pos);
			for (int i = 0; i < MODEL_TYPE_DIR.length; i++) {
				if (MODEL_TYPE_DIR[i].equals(type)) {
					return Integer.valueOf(i);
				}
			}
		}
		return Integer.valueOf(AbstractModel.MODEL_TYPE_BPMN);
	}

}
