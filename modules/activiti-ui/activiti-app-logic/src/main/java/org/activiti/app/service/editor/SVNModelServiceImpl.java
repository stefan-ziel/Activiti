/* $Id: SVNModelServiceImpl.java 58143 2017-10-03 15:50:34Z zis $ */

package org.activiti.app.service.editor;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.activiti.app.domain.editor.AbstractModel;
import org.activiti.app.domain.editor.AppDefinition;
import org.activiti.app.domain.editor.AppModelDefinition;
import org.activiti.app.domain.editor.Model;
import org.activiti.app.domain.editor.ModelHistory;
import org.activiti.app.model.editor.ReviveModelResultRepresentation;
import org.activiti.app.model.editor.ReviveModelResultRepresentation.UnresolveModelRepresentation;
import org.activiti.app.security.SecurityUtils;
import org.activiti.app.service.exception.InternalServerErrorException;
import org.activiti.engine.identity.User;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * A file model service that uses SVN to get history and commit models.
 */
public class SVNModelServiceImpl extends FileSystemModelServiceImpl {

	static final Logger LOGGER = Logger.getLogger(SVNModelServiceImpl.class);

	private static SAXParserFactory parserFactory = SAXParserFactory.newInstance();

	/**
	 * @param pSvnRootDir
	 */
	public SVNModelServiceImpl(File pSvnRootDir) {
		super(pSvnRootDir);
	}

	/**
	 * @return svn is installed
	 */
	public static boolean isInstalled(){
		ProcessBuilder procBuilder = new ProcessBuilder();
		procBuilder.command().add("svn"); //$NON-NLS-1$
		procBuilder.command().add("--version"); //$NON-NLS-1$
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug(procBuilder.command().toString());
		}
		try {
			Process proc = procBuilder.start();
			return proc.waitFor() == 0;
		}
		catch (IOException | InterruptedException e) {
			return false;
		}
	}

	@Override
	public List<ModelHistory> getModelHistory(Model pModel) {
		try {
			return svnLog(SecurityUtils.getCurrentUserObject(), pModel.getModelType(), pModel.getKey(), null);
		}
		catch (IOException e) {
			LOGGER.error("Could not list history for " + pModel.getId(), e); //$NON-NLS-1$
			throw new InternalServerErrorException("Could not list history for " + pModel.getId(), e); //$NON-NLS-1$
		}
	}

	@Override
	public ModelHistory getModelHistory(String pModelHistoryId) {
		return getHistory(pModelHistoryId);
	}

	@Override
	public ModelHistory getModelHistory(String pModelId, String pModelHistoryId) {
		return getHistory(pModelHistoryId);
	}

	@Override
	public List<ModelHistory> getModelHistoryForUser(User pUser, Integer pModelType) {
		try {
			return svnLog(pUser, pModelType, null, null);
		}
		catch (IOException e) {
			LOGGER.error("Could not list history for " + pModelType, e); //$NON-NLS-1$
			throw new InternalServerErrorException("Could not list history for " + pModelType, e); //$NON-NLS-1$
		}
	}

	@Override
	public ReviveModelResultRepresentation reviveProcessModelHistory(ModelHistory pModelHistory, User pUser, String pNewVersionComment) {
		Model latestModel = getModel(pModelHistory.getModelId());
		latestModel.setModelEditorJson(pModelHistory.getModelEditorJson());
		persistModel(latestModel, true, pNewVersionComment, pUser);
		ReviveModelResultRepresentation result = new ReviveModelResultRepresentation();

		// For apps, we need to make sure the referenced processes exist as models.
		// It could be the user has deleted the process model in the meantime. We
		// give back that info to the user.
		if (latestModel.getModelType().intValue() == AbstractModel.MODEL_TYPE_APP) {
			if (StringUtils.isNotEmpty(latestModel.getModelEditorJson())) {
				try {
					AppDefinition appDefinition = objectMapper.readValue(latestModel.getModelEditorJson(), AppDefinition.class);
					for (AppModelDefinition appModelDefinition : appDefinition.getModels()) {
						if (!getFile(appModelDefinition.getId()).exists()) {
							result.getUnresolvedModels().add(new UnresolveModelRepresentation(appModelDefinition.getId(), appModelDefinition.getName(), appModelDefinition.getLastUpdatedBy()));
						}
					}
				}
				catch (Exception e) {
					LOGGER.error("Could not deserialize app model json (id = " + latestModel.getId() + ")", e); //$NON-NLS-1$ //$NON-NLS-2$
				}
			}
		}
		return result;
	}

	@Override
	protected void deleteFile(User pUser, File pFile, String pComment) throws IOException {
		svnDelete(pFile);
		svnCommit(pUser, pFile, pComment);
	}

	@Override
	protected ModelHistory getVersion(File pFile) {
		String log = null;
		try {
			SVNLogHandler handler = new SVNLogHandler(null, null, null);
			log = svnExecute(null, "info", true, pFile, "--xml"); //$NON-NLS-1$ //$NON-NLS-2$
			if (log != null && log.startsWith("<?xml")) { //$NON-NLS-1$
				parse(log, handler);
				if(handler.version != null){
					handler.version.setVersion(handler.version.getVersion() + 1);
					return handler.version;
				}
			}
		}
		catch (IOException e) {
			LOGGER.error(e.getMessage()); 
		}
		return super.getVersion(pFile);
	}

	@Override
	protected void persistModel(Model pModel, boolean pNewVersion, String pComment, User pUpdatedBy) {
		try {
			super.persistModel(pModel, pNewVersion, pComment, pUpdatedBy);
			if (pNewVersion) {
				File file = getFile(pModel.getId());
				File toCommit = svnAdd(file);
				if (toCommit == null) {
					toCommit = file;
				}
				// Commit it
				svnCommit(pUpdatedBy, toCommit, pComment);
			}
		}
		catch (IOException e) {
			LOGGER.error("Could not commit model file", e); //$NON-NLS-1$
			throw new InternalServerErrorException("Could not commit model file", e); //$NON-NLS-1$
		}
	}

	/**
	 * @param pFile the file to add
	 * @return a ancestor folder that was added or null if none
	 * @throws IOException
	 */
	protected File svnAdd(File pFile) throws IOException {
		File res = null;
		if (SvnStat.NOT_ADDED.equals(svnStat(pFile))) {
			res = svnAdd(pFile.getParentFile());
			if (res == null) {
				svnExecute(null, "add", false, pFile); //$NON-NLS-1$
				res = pFile;
			}
		}
		return res;
	}

	/**
	 * commit a file
	 * 
	 * @param pUser the user
	 * @param pFile the file
	 * @param pComment a commit comment
	 * @throws IOException
	 */
	protected void svnCommit(User pUser, File pFile, String pComment) throws IOException {
		svnExecute(pUser, "commit", false, pFile, "--message", pComment); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * delete a file from svn
	 * 
	 * @param pFile the file
	 * @throws IOException
	 */
	protected void svnDelete(File pFile) throws IOException {
		svnExecute(null, "delete", false, pFile); //$NON-NLS-1$
	}
	
	/**
	 * execute a svn commandline and return the console output
	 * 
	 * @param pUser user for this operation. may be null
	 * @param pCommand the svn-command
	 * @param pIgnoreError ignor any SVN Arrors eg. for files not under version control.
	 * @param pFile the path
	 * @param pParams aditional params
	 * @return console output
	 * @throws IOException execution error
	 */
	protected String svnExecute(User pUser, String pCommand, boolean pIgnoreError, File pFile, String... pParams) throws IOException {
		ProcessBuilder procBuilder = new ProcessBuilder();
		procBuilder.command().add("svn"); //$NON-NLS-1$
		procBuilder.command().add(pCommand);
		if (pUser != null) {
			procBuilder.command().add("--username"); //$NON-NLS-1$
			procBuilder.command().add(pUser.getId());
			procBuilder.command().add("--password"); //$NON-NLS-1$
			procBuilder.command().add(pUser.getPassword());
		}
		procBuilder.command().addAll(Arrays.asList(pParams));
		procBuilder.command().add(pFile.getAbsolutePath());
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug(procBuilder.command().toString());
		}
		try {
			Process proc = procBuilder.start();
			Piper outputBuffer = new Piper(proc.getInputStream());
			Piper errorBuffer = new Piper(proc.getErrorStream());
			outputBuffer.start();
			errorBuffer.start();
			int exitCode = proc.waitFor();
			if (exitCode != 0) {
				if(pIgnoreError) {
					return ""; //$NON-NLS-1$
				}
				while (errorBuffer.isAlive()) {
					// busy waiting for a few millies just to be sure all bytes are read.
				}
				throw new IOException("External command failed with exit code " + exitCode + ':' + errorBuffer.toString()); //$NON-NLS-1$
			}
			while (outputBuffer.isAlive()) {
				// busy waiting for a few millies just to be sure all bytes are read.
			}
			String terminal = outputBuffer.toString();
			LOGGER.debug(terminal);
			return terminal;
		}
		catch (InterruptedException irex) {
			LOGGER.error(irex);
			throw new IOException("External command interrupted"); //$NON-NLS-1$
		}
	}

	/**
	 * Do a svn log
	 * 
	 * @param pUser the user. may be null
	 * @param pKey the object key to retrieve history
	 * @param pModelType type of model. may be null
	 * @param pRevision a fixed revision. null for all
	 * @return
	 * @throws IOException
	 */
	protected List<ModelHistory> svnLog(User pUser, Integer pModelType, String pKey, String pRevision) throws IOException {
		List<ModelHistory> res = new ArrayList<>();
		String log;
		if (pRevision == null && pKey == null) {
			log = svnExecute(pUser, "log", true, getTypeDir(pModelType), "-v", "--xml"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		} else if (pKey == null) {
			log = svnExecute(pUser, "log", true, getTypeDir(pModelType), "-r", pRevision, "-v", "--xml"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		} else if (pRevision == null) {
			log = svnExecute(pUser, "log", true, getFile(getId(pModelType, pKey)), "--xml"); //$NON-NLS-1$ //$NON-NLS-2$
		} else {
			log = svnExecute(pUser, "log", true, getFile(getId(pModelType, pKey)), "-r", pRevision, "--xml"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		if(log != null && !log.isEmpty() && log.startsWith("<?xml")){ //$NON-NLS-1$
			parse(log, new SVNLogHandler(pModelType, pKey, res));
		}
		return res;
	}

	/**
	 * get status info
	 * 
	 * @param pFile the file
	 * @return
	 * @throws IOException
	 */
	protected SvnStat svnStat(File pFile) throws IOException {
		String stat = svnExecute(null, "stat", true, pFile, "--depth", "empty"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		if (stat.length() == 0) {
			return SvnStat.CLEAN;
		}

		switch (stat.charAt(0)) {
			case '?':
				return SvnStat.NOT_ADDED;
			case 'M':
				return SvnStat.MODIFIED;
			case 'A':
				return SvnStat.NEW;
			case 'D':
				return SvnStat.DELETED;
		}
		return SvnStat.OTHERS;
	}

	ModelHistory getHistory(String pHistoryId) {

		try {
			String id = getModelId(pHistoryId);
			String revision = getRevision(pHistoryId);
			List<ModelHistory> list = svnLog(SecurityUtils.getCurrentUserObject(), getModelType(id), getModelKey(id), revision);
			if (list.size() == 0) {
				throw new InternalServerErrorException("Could not find history for " + pHistoryId); //$NON-NLS-1$
			}
			String text = svnExecute(null, "cat", false, getFile(id), "-r", revision); //$NON-NLS-1$ //$NON-NLS-2$
			Model model = loadModel(getModelType(id), getModelKey(id), list.get(0), new StringReader(text));
			return createNewModelhistory(model);

		}
		catch (IOException e) {
			LOGGER.error("Could not load history for " + pHistoryId, e); //$NON-NLS-1$
			throw new InternalServerErrorException("Could not load history for " + pHistoryId, e); //$NON-NLS-1$
		}
	}

	String getModelId(String pHistoryId) {
		int pos = pHistoryId.lastIndexOf('-');
		return pHistoryId.substring(0, pos) + '}';
	}

	String getRevision(String pHistoryId) {
		int pos = pHistoryId.lastIndexOf('-');
		return pHistoryId.substring(pos + 1, pHistoryId.length() - 1);
	}

	int getVersion(String pHistoryId) {
		int pos = pHistoryId.lastIndexOf('-');
		return Integer.parseInt(pHistoryId.substring(pos + 1, pHistoryId.length()));
	}

	void parse(String pXMLText, ContentHandler pHandler) throws IOException {
		try {
			XMLReader reader = parserFactory.newSAXParser().getXMLReader();
			reader.setContentHandler(pHandler);
			reader.parse(new InputSource(new StringReader(pXMLText)));
		}
		catch (SAXException | ParserConfigurationException e) {
			LOGGER.error("Invalid XML: " + pXMLText, e); //$NON-NLS-1$
			throw new IOException(e);
		}
	}

	class SVNLogHandler extends DefaultHandler {

		Integer type;
		String key;
		String path;
		SvnStat stat;
		List<ModelHistory> res;
		StringBuffer text;
		ModelHistory version;

		public SVNLogHandler(Integer pType, String pKey, List<ModelHistory> pRes) {
			super();
			res = pRes;
			type = pType == null ? Integer.valueOf(AbstractModel.MODEL_TYPE_BPMN) : pType;
			key = pKey;
		}

		@Override
		public void characters(char[] pCh, int pStart, int pLength) throws SAXException {
			if (text != null) {
				text.append(pCh, pStart, pLength);
			}
		}

		@Override
		public void endElement(String pUri, String pLocalName, String pQName) throws SAXException {
			switch (pQName) {
				case "author": //$NON-NLS-1$
					version.setLastUpdatedBy(endText());
					break;
				case "msg": //$NON-NLS-1$
					version.setComment(endText());
					break;
				case "date": //$NON-NLS-1$
					try {
						version.setLastUpdated(objectMapper.getDeserializationConfig().getDateFormat().parse(endText()));
					}
					catch (ParseException e) {
						throw new SAXException(e);
					}
					break;
				case "path": //$NON-NLS-1$
					if (key == null) {
						path = endText();
						int pos = path.lastIndexOf('/');
						if (pos >= 0) {
							path = path.substring(pos + 1);
						}
						if (path.endsWith(EXTENSION)) {
							path = path.substring(0, key.length() - EXTENSION.length());
						} else {
							path = null;
						}
					}
					break;
				case "logentry": //$NON-NLS-1$
					if (type != null && key != null && version != null) {
						version.setId(getHistoryId(type, key, version.getVersion()));
						version.setModelId(getId(type, key));
						version.setModelType(type);
						version.setKey(key);
						res.add(version);
					}
					break;
			}
		}

		@Override
		public void startElement(String pUri, String pLocalName, String pQName, Attributes pAttributes) throws SAXException {
			switch (pQName) {
				case "commit": //$NON-NLS-1$
				case "logentry": //$NON-NLS-1$
					version = new ModelHistory();
					version.setVersion(Integer.parseInt(pAttributes.getValue("revision"))); //$NON-NLS-1$
					path = key;
					break;
				case "author": //$NON-NLS-1$
				case "date": //$NON-NLS-1$
				case "msg": //$NON-NLS-1$
					startText();
					break;
				case "path": //$NON-NLS-1$
					String action = pAttributes.getValue("action"); //$NON-NLS-1$
					switch (action) {
						case "A": //$NON-NLS-1$
							stat = SvnStat.NEW;
							break;
						case "M": //$NON-NLS-1$
							stat = SvnStat.MODIFIED;
							break;
						case "D": //$NON-NLS-1$
							stat = SvnStat.MODIFIED;
							break;
						default:
							stat = SvnStat.OTHERS;
							break;
					}
					startText();
					break;
			}
		}

		String endText() {
			String s = text.toString();
			text = null;
			return s;
		}

		void startText() {
			text = new StringBuffer();
		}
	}

	enum SvnStat {
		CLEAN, NOT_ADDED, NEW, MODIFIED, DELETED, OTHERS;
	}

	private static class Piper extends Thread {

		private BufferedReader input;
		StringBuilder buffer = new StringBuilder();
		String result;

		/**
		 * @param pInputStream Stream source
		 */
		public Piper(InputStream pInputStream) {
			input = new BufferedReader(new InputStreamReader(pInputStream));
		}

		@Override
		public void run() {
			this.setName("CommandExecuteThread"); //$NON-NLS-1$

			try {
				while (true) {
					int c = input.read();
					if (c == -1) {
						break;
					}
					buffer.append((char) c);
				}
				result = buffer.toString();
			}
			catch (IOException ioe) {
				LOGGER.error(ioe);
			}
		}

		/**
		 * @return return all content read
		 */
		@Override
		public String toString() {
			return result;
		}
	}

}
