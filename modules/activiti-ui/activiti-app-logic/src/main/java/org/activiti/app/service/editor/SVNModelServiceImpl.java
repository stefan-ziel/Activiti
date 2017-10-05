/* $Id: SVNModelServiceImpl.java 58143 2017-10-03 15:50:34Z zis $ */

package org.activiti.app.service.editor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.activiti.app.domain.editor.Model;
import org.activiti.app.domain.editor.ModelHistory;
import org.activiti.app.security.SecurityUtils;
import org.activiti.app.service.editor.FileSystemModelServiceImpl;
import org.activiti.app.service.exception.InternalServerErrorException;
import org.activiti.engine.identity.User;
import org.activiti.engine.impl.identity.Authentication;
import org.apache.log4j.Logger;
import org.springframework.security.core.userdetails.UserDetails;
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

	private static final Logger LOGGER = Logger.getLogger(SVNModelServiceImpl.class);
	
	private static SAXParserFactory parserFactory = SAXParserFactory.newInstance();
	
	/**
	 * @param pSvnRootDir
	 */
	public SVNModelServiceImpl(File pSvnRootDir) {
		super(pSvnRootDir);
	}

	@Override
	public List<ModelHistory> getModelHistory(Model pModel) {
		try {
			List<ModelHistory> res = new ArrayList<>();
			File dir = getFile(pModel.getId());
			String log = svnExecute("log", dir, "--xml"); //$NON-NLS-1$ //$NON-NLS-2$
			parse(log, new SVNLogHandler(null, pModel, res));
			return res;
		}
		catch (SAXException | IOException | ParserConfigurationException e) {
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
			List<ModelHistory> res = new ArrayList<>();
			File dir = getTypeDir(pModelType);
			String log = svnExecute("log", dir, "-v", "--xml"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			parse(log, new SVNLogHandler(pModelType, null, res));
			return res;
		}
		catch (SAXException | IOException | ParserConfigurationException e) {
			LOGGER.error("Could not list history for " + pModelType, e); //$NON-NLS-1$
			throw new InternalServerErrorException("Could not list history for " + pModelType, e); //$NON-NLS-1$
		}
	}

	@Override
	protected void deleteFile(File pFile) throws IOException {
		svnDelete(pFile);
	}

	/**
	 * @return return a user for SVN commits
	 */
	protected UserDetails getSVNUser() {
		return SecurityUtils.getCurrentActivitiAppUser();
	}

	@Override
	protected Model loadModel(String pModelId, File pModelFile) throws IOException, ParseException {
		Model model = super.loadModel(pModelId, pModelFile);
		svnInfo(pModelFile, model);
		return model;
	}

	@Override
	protected Model persistModel(Model pModel, boolean pNewVersion, String pComment, User pUpdatedBy) {
		try {
			Model newModel = super.persistModel(pModel, pNewVersion, pComment, pUpdatedBy);
			if (pNewVersion) {
				File file = getFile(pModel.getId());
				File toCommit = svnAdd(file);
				if (toCommit == null) {
					toCommit = file;
				}
				svnExecute("commit", toCommit, "--message", pComment); //$NON-NLS-1$ //$NON-NLS-2$
				svnInfo(file, newModel);
			}
			return newModel;
		}
		catch (IOException e) {
			LOGGER.error("Could not commit model file", e); //$NON-NLS-1$
			throw new InternalServerErrorException("Could not commit model file", e); //$NON-NLS-1$
		}
	}

	ModelHistory getHistory(String pHistoryId) {
		try {
			int pos = pHistoryId.lastIndexOf('-');
			String id = pHistoryId.substring(0, pos) + '}';
			String revision = pHistoryId.substring(pos + 1, pHistoryId.length() - 1);
			File file = getFile(id);
			int type = getModelType(id);
			SVNLogHandler handler = new SVNLogHandler(Integer.valueOf(type), null, null);
			String log = svnExecute("log", file, "-r", revision, "--xml"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			parse(log, handler);
			Model model = getModel(id);
			ModelHistory historyModel = new ModelHistory();
			historyModel.setName(model.getName());
			historyModel.setKey(getModelKey(id));
			historyModel.setDescription(model.getDescription());
			historyModel.setCreated(model.getCreated());
			historyModel.setLastUpdated(handler.time);
			historyModel.setCreatedBy(model.getCreatedBy());
			historyModel.setLastUpdatedBy(handler.autor);
			historyModel.setModelEditorJson(model.getModelEditorJson());
			historyModel.setModelType(handler.type);
			historyModel.setVersion(handler.revision);
			historyModel.setComment(handler.comment);
			historyModel.setModelId(id);
			historyModel.setId(pHistoryId);
			return historyModel;
		}
		catch (IOException | SAXException | ParserConfigurationException e) {
			LOGGER.error("Could not list history for " + pHistoryId, e); //$NON-NLS-1$
			throw new InternalServerErrorException("Could not list history for " + pHistoryId, e); //$NON-NLS-1$
		}

	}

	int getVersion(String pHistoryId) {
		int pos = pHistoryId.lastIndexOf('-');
		return Integer.parseInt(pHistoryId.substring(pos + 1, pHistoryId.length()));
	}

	void parse(String pXMLText, ContentHandler pHandler) throws SAXException, IOException, ParserConfigurationException {
		XMLReader reader = parserFactory.newSAXParser().getXMLReader();
		reader.setContentHandler(pHandler);
		reader.parse(new InputSource(new StringReader(pXMLText)));
	}

	File svnAdd(File pFile) throws IOException {
		File res = null;
		if (SvnStat.NOT_ADDED.equals(svnStat(pFile))) {
			res = svnAdd(pFile.getParentFile());
			if (res == null) {
				svnExecute("add", pFile); //$NON-NLS-1$
				res = pFile;
			}
		}
		return res;
	}

	void svnDelete(File pFile) throws IOException {
		svnExecute("delete", pFile); //$NON-NLS-1$
	}

	String svnExecute(String pCommand, File pFile, String... pParams) throws IOException {
		UserDetails user = getSVNUser();
		ProcessBuilder procBuilder = new ProcessBuilder();
		procBuilder.command().add("svn"); //$NON-NLS-1$
		procBuilder.command().add(pCommand);
		if (user != null) {
			procBuilder.command().add("--username"); //$NON-NLS-1$
			procBuilder.command().add(user.getUsername());
			procBuilder.command().add("--password"); //$NON-NLS-1$
			procBuilder.command().add(user.getPassword());
		}
		procBuilder.command().addAll(Arrays.asList(pParams));
		procBuilder.command().add(pFile.getAbsolutePath());
		if(LOGGER.isDebugEnabled()){
			LOGGER.debug(procBuilder.command().toString());
		}
		try{ 
		Process proc = procBuilder.start();
		Piper outputBuffer = new Piper(proc.getInputStream());
		Piper errorBuffer = new Piper(proc.getInputStream());
		outputBuffer.start();
		errorBuffer.start();
		int exitCode = proc.waitFor();
		if (exitCode != 0) {
			throw new IOException("External command failed with error:\n" + errorBuffer.toString()); //$NON-NLS-1$
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

	void svnInfo(File pFile, Model pModel) throws IOException {
		try {
			SVNLogHandler handler = new SVNLogHandler(null, null, null);
			String log = svnExecute("info", pFile, "--xml"); //$NON-NLS-1$ //$NON-NLS-2$
			parse(log, handler);
			pModel.setLastUpdated(handler.time);
			pModel.setLastUpdatedBy(handler.autor);
			pModel.setVersion(handler.revision);
			pModel.setComment(handler.comment);
			if (pModel.getCreated() == null) {
				pModel.setCreated(handler.time);
			}
			if (pModel.getCreatedBy() == null) {
				pModel.setCreatedBy(handler.autor);
			}
		}
		catch (SAXException | ParserConfigurationException e) {
			if (pModel.getLastUpdated() == null) {
				pModel.setLastUpdated(new Date());
			}
			if (pModel.getLastUpdated() == null) {
				pModel.setLastUpdatedBy(Authentication.getAuthenticatedUserId());
			}
			if (pModel.getCreated() == null) {
				pModel.setCreated(new Date());
			}
			if (pModel.getCreatedBy() == null) {
				pModel.setCreatedBy(Authentication.getAuthenticatedUserId());
			}
		}
	}

	SvnStat svnStat(File pFile) throws IOException {
		String stat = svnExecute("stat", pFile, "--depth", "empty"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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

	class SVNLogHandler extends DefaultHandler {

		Model model;
		Integer type;
		List<ModelHistory> res;
		StringBuffer text;
		SvnStat stat;
		String autor;
		String comment;
		Date time;
		int revision;

		public SVNLogHandler(Integer pType, Model pModel, List<ModelHistory> pRes) {
			super();
			res = pRes;
			type = pType;
			model = pModel;
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
					autor = endText();
					break;
				case "msg": //$NON-NLS-1$
					comment = endText();
					break;
				case "date": //$NON-NLS-1$
					try {
						time = JSON_DATE_FORMAT.parse(endText());
					}
					catch (ParseException e) {
						throw new SAXException(e);
					}
					break;
				case "path": //$NON-NLS-1$
					String key = endText();
					int pos = key.lastIndexOf('/');
					if (pos >= 0) {
						key = key.substring(pos + 1);
					}
					if (key.endsWith(EXTENSION)) {
						key = key.substring(0, key.length() - EXTENSION.length());
						String id = getId(type, key);
						Model aModel = getModel(id);
						ModelHistory historyModel = new ModelHistory();
						historyModel.setName(aModel.getName());
						historyModel.setKey(key);
						historyModel.setDescription(aModel.getDescription());
						historyModel.setCreated(aModel.getCreated());
						historyModel.setLastUpdated(time);
						historyModel.setCreatedBy(aModel.getCreatedBy());
						historyModel.setLastUpdatedBy(autor);
						historyModel.setModelEditorJson(aModel.getModelEditorJson());
						historyModel.setModelType(type);
						historyModel.setVersion(revision);
						historyModel.setComment(comment);
						historyModel.setModelId(id);
						historyModel.setId(getHistoryId(type, key, revision));
						res.add(historyModel);
					}
					break;
				case "logentry": //$NON-NLS-1$
					if (model != null) {
						ModelHistory historyModel = new ModelHistory();
						historyModel.setName(model.getName());
						historyModel.setKey(model.getKey());
						historyModel.setDescription(model.getDescription());
						historyModel.setCreated(model.getCreated());
						historyModel.setLastUpdated(time);
						historyModel.setCreatedBy(model.getCreatedBy());
						historyModel.setLastUpdatedBy(autor);
						historyModel.setModelEditorJson(model.getModelEditorJson());
						historyModel.setModelType(type);
						historyModel.setVersion(revision);
						historyModel.setComment(comment);
						historyModel.setModelId(model.getId());
						historyModel.setId(getHistoryId(model.getModelType(), model.getKey(), revision));
						res.add(historyModel);
					}
					break;
			}
		}

		@Override
		public void startElement(String pUri, String pLocalName, String pQName, Attributes pAttributes) throws SAXException {
			switch (pQName) {
				case "commit": //$NON-NLS-1$
				case "logentry": //$NON-NLS-1$
					revision = Integer.parseInt(pAttributes.getValue("revision")); //$NON-NLS-1$
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

		private InputStream inputStream;
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

		/**
		 * @param pInputStream Stream Quelle
		 */
		public Piper(InputStream pInputStream) {
			inputStream = pInputStream;
		}

		/**
		 * @see java.lang.Runnable#run()
		 */
		@Override
		public void run() {
			this.setName("CommandExecuteThread"); //$NON-NLS-1$

			try {
				byte buffer[] = new byte[4096];
				int bytesRead;
				while ((bytesRead = inputStream.read(buffer)) != -1) {
					outputStream.write(buffer, 0, bytesRead);
				}
			}
			catch (IOException ioe) {
				// NOP
			}
		}

		/**
		 * @return return all content read
		 * @see java.lang.Thread#toString()
		 */
		@Override
		public String toString() {
			return outputStream.toString();
		}
	}


}
