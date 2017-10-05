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
import java.util.Date;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.activiti.app.domain.editor.Model;
import org.activiti.app.domain.editor.ModelHistory;
import org.activiti.app.service.editor.FileSystemModelServiceImpl;
import org.activiti.app.service.exception.InternalServerErrorException;
import org.activiti.engine.identity.User;
import org.activiti.engine.impl.identity.Authentication;
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

	@Override
	public List<ModelHistory> getModelHistory(Model pModel) {
		String log = null;
		try {
			List<ModelHistory> res = new ArrayList<>();
			File file = getFile(pModel.getId());
			log = svnExecute(null,"log", file, "-r", "PREV:0", "--xml"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			if (log != null && log.startsWith("<?xml")) { //$NON-NLS-1$
				parse(log, new SVNLogHandler(pModel.getModelType(), pModel.getKey(), res));
			}
			return res;
		}
		catch (SAXException e) {
			LOGGER.error("Invalid xml: " + log, e); //$NON-NLS-1$
			throw new InternalServerErrorException("Invalid xml for " + pModel.getId(), e); //$NON-NLS-1$
		}
		catch (IOException | ParserConfigurationException e) {
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
			String log = svnExecute(pUser, "log", dir, "-v", "--xml"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			parse(log, new SVNLogHandler(pModelType, null, res));
			return res;
		}
		catch (SAXException | IOException | ParserConfigurationException e) {
			LOGGER.error("Could not list history for " + pModelType, e); //$NON-NLS-1$
			throw new InternalServerErrorException("Could not list history for " + pModelType, e); //$NON-NLS-1$
		}
	}

	@Override
	protected void deleteFile(User pUser, File pFile, String pComment) throws IOException {
		svnDelete(pFile);
		svnCommit(pUser, pFile, pComment); 
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
				// Commit it
				svnCommit(pUpdatedBy,toCommit, pComment); 
				// Read information of this new versison
				svnInfo(file, newModel);
				// and write it to the current 
				newModel = super.persistModel(newModel, false, null, null);
			}
			return newModel;
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
				svnExecute(null, "add", pFile); //$NON-NLS-1$
				res = pFile;
			}
		}
		return res;
	}

	/**
	 * commit a file 
	 * @param pUser the user 
	 * @param pFile the file
	 * @param pComment a commit comment
	 * @throws IOException 
	 */
	protected void svnCommit(User pUser, File pFile, String pComment) throws IOException {
		svnExecute(pUser,"commit", pFile, "--message", pComment); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * delete a file from svn
	 * @param pFile the file 
	 * @throws IOException
	 */
	protected void svnDelete(File pFile) throws IOException {
		svnExecute(null, "delete", pFile); //$NON-NLS-1$
	}

	/**
	 * execute a svn commandline and return the console output
	 * @param pUser user for this operation. may be null
	 * @param pCommand the svn-command
	 * @param pFile the path
	 * @param pParams aditional params 
	 * @return console output 
	 * @throws IOException execution error
	 */
	protected String svnExecute(User pUser, String pCommand, File pFile, String... pParams) throws IOException {
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
				while (errorBuffer.isAlive()) {
					// busy waiting for a few millies just to be sure all bytes are read.
				}
				throw new IOException("External command failed with exit code "+ exitCode+':' + errorBuffer.toString()); //$NON-NLS-1$
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
	 * get version info into the model
	 * @param pFile
	 * @param pModel
	 * @throws IOException
	 */
	protected void svnInfo(File pFile, Model pModel) throws IOException {
		String log = null;
		try {
			SVNLogHandler handler = new SVNLogHandler(null, null, null);
			log = svnExecute(null, "info", pFile, "--xml"); //$NON-NLS-1$ //$NON-NLS-2$
			if (log != null && log.startsWith("<?xml")) { //$NON-NLS-1$
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
			} else {
				if (pModel.getLastUpdated() == null) {
					pModel.setLastUpdated(new Date());
				}
				if (pModel.getLastUpdatedBy() == null) {
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
		catch (SAXException e) {
			LOGGER.error("Invalid XML :" + log, e); //$NON-NLS-1$
			throw new IOException(e);
		}
		catch (ParserConfigurationException e) {
			throw new IOException(e);
		}
	}

	/**
	 * get status info
	 * @param pFile the file 
	 * @return
	 * @throws IOException
	 */
	protected SvnStat svnStat(File pFile) throws IOException {
		String stat = svnExecute(null, "stat", pFile, "--depth", "empty"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
			int pos = pHistoryId.lastIndexOf('-');
			String id = pHistoryId.substring(0, pos) + '}';
			String revision = pHistoryId.substring(pos + 1, pHistoryId.length() - 1);
			String text = svnExecute(null, "cat", getFile(id), "-r", revision); //$NON-NLS-1$ //$NON-NLS-2$
			Model model = loadModel(id, new StringReader(text));
			return createNewModelhistory(model);
		}
		catch (IOException | ParseException e) {
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

	class SVNLogHandler extends DefaultHandler {

		Integer type;
		List<ModelHistory> res;
		StringBuffer text;
		SvnStat stat;
		String autor;
		String comment;
		Date time;
		int revision;
		String key;

		public SVNLogHandler(Integer pType, String pKey, List<ModelHistory> pRes) {
			super();
			res = pRes;
			type = pType;
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
					key = endText();
					int pos = key.lastIndexOf('/');
					if (pos >= 0) {
						key = key.substring(pos + 1);
					}
					if (key.endsWith(EXTENSION)) {
						key = key.substring(0, key.length() - EXTENSION.length());
					}
					break;
				case "logentry": //$NON-NLS-1$
					if (type != null && key != null && revision > 0) {
						ModelHistory historyModel = new ModelHistory();
						historyModel.setId(getHistoryId(type,key,revision));
						historyModel.setModelId(getId(type,key));
						historyModel.setKey(key);
						historyModel.setLastUpdated(time);
						historyModel.setLastUpdatedBy(autor);
						historyModel.setModelType(type);
						historyModel.setVersion(revision);
						historyModel.setComment(comment);
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
					comment = null;
					autor = null;
					time = null;
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
