/*
 * Copyright 2008-2013, 2014, 2015, 2016, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd;

import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.HttpdServer;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.HttpdSiteBind;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Manages web site logs.
 *
 * @author  AO Industries, Inc.
 */
class HttpdLogManager {

	/**
	 * The directory that contains the log rotation scripts.
	 */
	private static final String LOG_ROTATION_DIR_REDHAT = HttpdServerManager.CONF_DIRECTORY + "/logrotate.d";
	private static final String LOG_ROTATION_DIR_CENTOS_5 = HttpdServerManager.CONF_DIRECTORY + "/logrotate.sites";
	private static final String SERVER_LOG_ROTATION_DIR_CENTOS_5 = HttpdServerManager.CONF_DIRECTORY + "/logrotate.servers";
	// CentOS 7: we're putting all log rotations directly in /etc/logrotate.d to avoid required modification of /etc/logrotate.conf
	private static final String ETC_LOGROTATE_D = "/etc/logrotate.d";

	/**
	 * Logrotate file prefix used for HttpdSite in /etc/logrotate.d
	 */
	private static final String HTTPD_SITE_PREFIX = "httpd-site-";

	/**
	 * Logrotate file prefix used for HttpdServer
	 */
	private static final String HTTPD_SERVER_PREFIX = "httpd";

	/**
	 * Pattern to match HttpdServer when in /etc/logrotate.d
	 *
	 * TODO: First httpd as "httpd" not "httpd1".
	 */
	private static final Pattern HTTPD_SERVER_REGEXP = Pattern.compile("^" + HTTPD_SERVER_PREFIX + "[0-9]+$");

	/**
	 * The directory that contains the per-apache-instance logs.
	 */
	private static final UnixFile serverLogDir = new UnixFile("/var/log/httpd");

	/**
	 * Responsible for control of all things in /logs and /etc/httpd/conf/logrotate.d
	 *
	 * Only called by the already synchronized <code>HttpdManager.doRebuild()</code> method.
	 */
	static void doRebuild(
		List<File> deleteFileList,
		Set<HttpdServer> serversNeedingReloaded
	) throws IOException, SQLException {
		// Used below
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		AOServer aoServer = AOServDaemon.getThisAOServer();

		// Rebuild /logs
		doRebuildLogs(aoServer, deleteFileList, serversNeedingReloaded);

		// Rebuild /etc/logrotate.d or /etc/httpd/conf/logrotate.(d|sites|servers) files
		doRebuildLogrotate(aoServer, deleteFileList, bout);

		// Rebuild /var/log/httpd
		doRebuildVarLogHttpd(aoServer, deleteFileList);
	}

	/**
	 * Rebuilds the /var/log/httpd directory
	 */
	private static void doRebuildVarLogHttpd(AOServer aoServer, List<File> deleteFileList) throws IOException, SQLException {
		HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
		if(osConfig==HttpdOperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64) {

			// Remove any symlink at /var/log/httpd
			Stat serverLogDirStat = serverLogDir.getStat();
			if(serverLogDirStat.exists() && serverLogDirStat.isSymLink()) serverLogDir.delete();

			// Create /var/log/httpd if missing
			DaemonFileUtils.mkdir(serverLogDir, 0700, UnixFile.ROOT_UID, UnixFile.ROOT_GID);

			// Create all /var/log/httpd/* directories
			List<HttpdServer> hss = aoServer.getHttpdServers();
			Set<String> dontDeleteFilenames = new HashSet<>(hss.size()*4/3+1);
			for(HttpdServer hs : hss) {
				String filename = "httpd"+hs.getNumber();
				dontDeleteFilenames.add(filename);
				DaemonFileUtils.mkdir(new UnixFile(serverLogDir, filename, false), 0700, UnixFile.ROOT_UID, UnixFile.ROOT_GID);
			}

			// Remove any extra
			for(String filename : serverLogDir.list()) {
				if(!dontDeleteFilenames.contains(filename)) {
					if(!filename.startsWith("suexec.log")) deleteFileList.add(new UnixFile(serverLogDir, filename, false).getFile());
				}
			}
		}
	}

	/**
	 * Rebuilds the directories under /logs
	 */
	private static void doRebuildLogs(
		AOServer aoServer,
		List<File> deleteFileList,
		Set<HttpdServer> serversNeedingReloaded
	) throws IOException, SQLException {
		// Values used below
		final int awstatsUID = aoServer.getLinuxServerAccount(LinuxAccount.AWSTATS).getUid().getId();

		// The log directories that exist but are not used will be removed
		UnixPath logDir = aoServer.getServer().getOperatingSystemVersion().getHttpdSiteLogsDirectory();
		if(logDir != null) {
			UnixFile logDirUF = new UnixFile(logDir.toString());
			// Create the logs directory if missing
			if(!logDirUF.getStat().exists()) {
				logDirUF.mkdir(true, 0755, UnixFile.ROOT_UID, UnixFile.ROOT_GID);
			}

			String[] list = logDirUF.list();
			Set<String> logDirectories = new HashSet<>(list.length*4/3+1);
			for(String dirname : list) {
				if(!dirname.equals("lost+found")) logDirectories.add(dirname);
			}

			for(HttpdSite httpdSite : aoServer.getHttpdSites()) {
				int lsgGID = httpdSite.getLinuxServerGroup().getGid().getId();

				// Create the /logs/<site_name> directory
				String siteName = httpdSite.getSiteName();
				UnixFile logDirectory = new UnixFile(logDirUF, siteName, true);
				Stat logStat = logDirectory.getStat();
				if(!logStat.exists()) {
					logDirectory.mkdir();
					logStat = logDirectory.getStat();
				}
				if(logStat.getUid()!=awstatsUID || logStat.getGid()!=lsgGID) logDirectory.chown(awstatsUID, lsgGID);
				if(logStat.getMode()!=0750) logDirectory.setMode(0750);

				// Remove from list so it will not be deleted
				logDirectories.remove(siteName);

				// Make sure each log file referenced under HttpdSiteBinds exists
				List<HttpdSiteBind> hsbs = httpdSite.getHttpdSiteBinds();
				for(HttpdSiteBind hsb : hsbs) {
					// access_log
					UnixPath accessLog = hsb.getAccessLog();
					UnixFile accessLogFile = new UnixFile(accessLog.toString());
					Stat accessLogStat = accessLogFile.getStat();
					if(!accessLogStat.exists()) {
						// Make sure the parent directory exists
						UnixFile accessLogParent=accessLogFile.getParent();
						if(!accessLogParent.getStat().exists()) accessLogParent.mkdir(true, 0750, awstatsUID, lsgGID);
						// Create the empty logfile
						new FileOutputStream(accessLogFile.getFile(), true).close();
						accessLogStat = accessLogFile.getStat();
						// Need to restart servers if log file created
						for(HttpdSiteBind hsb2 : hsbs) {
							if(hsb2.getAccessLog().equals(accessLog)) {
								serversNeedingReloaded.add(hsb2.getHttpdBind().getHttpdServer());
							}
						}
					}
					if(accessLogStat.getMode()!=0640) accessLogFile.setMode(0640);
					if(accessLogStat.getUid()!=awstatsUID || accessLogStat.getGid()!=lsgGID) accessLogFile.chown(awstatsUID, lsgGID);

					// error_log
					UnixPath errorLog = hsb.getErrorLog();
					UnixFile errorLogFile = new UnixFile(errorLog.toString());
					Stat errorLogStat = errorLogFile.getStat();
					if(!errorLogStat.exists()) {
						// Make sure the parent directory exists
						UnixFile errorLogParent=errorLogFile.getParent();
						if(!errorLogParent.getStat().exists()) errorLogParent.mkdir(true, 0750, awstatsUID, lsgGID);
						// Create the empty logfile
						new FileOutputStream(errorLogFile.getFile(), true).close();
						errorLogStat = errorLogFile.getStat();
						// Need to restart servers if log file created
						for(HttpdSiteBind hsb2 : hsbs) {
							if(hsb2.getErrorLog().equals(errorLog)) {
								serversNeedingReloaded.add(hsb2.getHttpdBind().getHttpdServer());
							}
						}
					}
					if(errorLogStat.getMode()!=0640) errorLogFile.setMode(0640);
					if(errorLogStat.getUid()!=awstatsUID || errorLogStat.getGid()!=lsgGID) errorLogFile.chown(awstatsUID, lsgGID);
				}
			}

			for(String filename : logDirectories) deleteFileList.add(new File(logDirUF.getFile(), filename));
		}
	}

	/**
	 * Rebuilds the per-site logrotation files.
	 */
	private static void doRebuildLogrotate(
		AOServer thisAoServer,
		List<File> deleteFileList,
		ByteArrayOutputStream byteOut
	) throws IOException, SQLException {
		final HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
		final String siteLogRotationDir;
		final String serverLogRotationDir;
		final boolean isGlobalLogrotateDir;
		switch(osConfig) {
			case REDHAT_ES_4_X86_64 :
				siteLogRotationDir = LOG_ROTATION_DIR_REDHAT;
				serverLogRotationDir = null;
				isGlobalLogrotateDir = false;
				break;
			case CENTOS_5_I686_AND_X86_64 :
				siteLogRotationDir = LOG_ROTATION_DIR_CENTOS_5;
				serverLogRotationDir = SERVER_LOG_ROTATION_DIR_CENTOS_5;
				isGlobalLogrotateDir = false;
				break;
			case CENTOS_7_X86_64 :
				siteLogRotationDir = ETC_LOGROTATE_D;
				serverLogRotationDir = ETC_LOGROTATE_D;
				isGlobalLogrotateDir = true;
				break;
			default :
				throw new AssertionError("Unexpected value for osConfig: "+osConfig);
		}

		int uid_min = thisAoServer.getUidMin().getId();
		int gid_min = thisAoServer.getGidMin().getId();

		// Create directory if missing
		if(!isGlobalLogrotateDir) {
			DaemonFileUtils.mkdir(siteLogRotationDir, 0700, UnixFile.ROOT_UID, UnixFile.ROOT_GID);
		}

		// The log rotations that exist but are not used will be removed
		String[] list = new File(siteLogRotationDir).list();
		Set<String> logRotationFiles = new HashSet<>(list.length*4/3+1);
		for(String filename : list) {
			if(
				!isGlobalLogrotateDir
				|| filename.startsWith(HTTPD_SITE_PREFIX)
			) {
				logRotationFiles.add(filename);
			}
		}

		// Each log file will be only rotated at most once
		Set<UnixPath> completedPaths = new HashSet<>(list.length*4/3+1);

		// For each site, build/rebuild the logrotate.d file as necessary and create any necessary log files
		ChainWriter chainOut=new ChainWriter(byteOut);
		for(HttpdSite site : thisAoServer.getHttpdSites()) {
			// Write the new file to RAM first
			byteOut.reset();
			boolean wroteOne = false;
			for(HttpdSiteBind bind : site.getHttpdSiteBinds()) {
				UnixPath access_log = bind.getAccessLog();
				// Each unique path is only rotated once
				if(completedPaths.add(access_log)) {
					// Add to the site log rotation
					if(wroteOne) chainOut.print(' ');
					else wroteOne = true;
					chainOut.print(access_log);
				}
				UnixPath error_log = bind.getErrorLog();
				if(completedPaths.add(error_log)) {
					// Add to the site log rotation
					if(wroteOne) chainOut.print(' ');
					else wroteOne = true;
					chainOut.print(error_log);
				}
			}
			// Do not write empty files, finish the file
			if(wroteOne) {
				chainOut.print(" {\n"
							 + "    missingok\n"
							 + "    daily\n"
							 + "    rotate 379\n"
							 + "}\n");
				chainOut.flush();
				byte[] newFileContent=byteOut.toByteArray();

				// Write to disk if file missing or doesn't match
				DaemonFileUtils.writeIfNeeded(
					newFileContent,
					null,
					new UnixFile(siteLogRotationDir, site.getSiteName()),
					UnixFile.ROOT_UID,
					site.getLinuxServerGroup().getGid().getId(),
					0640,
					uid_min,
					gid_min
				);

				// Make sure the newly created or replaced log rotation file is not removed
				logRotationFiles.remove(site.getSiteName());
			}
		}

		// Remove extra filenames
		for(String extraFilename : logRotationFiles) {
			deleteFileList.add(new File(siteLogRotationDir, extraFilename));
		}

		if(serverLogRotationDir != null) {
			// Create directory if missing
			if(!isGlobalLogrotateDir) {
				DaemonFileUtils.mkdir(serverLogRotationDir, 0700, UnixFile.ROOT_UID, UnixFile.ROOT_GID);
			}

			// The log rotations that exist but are not used will be removed
			logRotationFiles.clear();
			for(String filename : new File(serverLogRotationDir).list()) {
				if(
					!isGlobalLogrotateDir
					|| HTTPD_SERVER_REGEXP.matcher(filename).matches()
				) {
					logRotationFiles.add(filename);
				}
			}

			boolean isFirst = true;
			for(HttpdServer hs : thisAoServer.getHttpdServers()) {
				int num = hs.getNumber();
				String filename = HTTPD_SERVER_PREFIX + num;
				logRotationFiles.remove(filename);

				// Build to RAM first
				byteOut.reset();
				try (ChainWriter out = new ChainWriter(byteOut)) {
					out.write("/var/log/httpd/httpd").print(num).print("/access_log {\n"
							+ "    missingok\n"
							+ "    daily\n"
							+ "    rotate 379\n"
							+ "}\n"
							+ "/var/log/httpd/httpd").print(num).print("/error_log {\n"
							+ "    missingok\n"
							+ "    daily\n"
							+ "    rotate 379\n"
							+ "}\n"
							+ "/var/log/httpd/httpd").print(num).print("/jserv.log {\n"
							+ "    missingok\n"
							+ "    daily\n"
							+ "    rotate 379\n"
							+ "}\n"
							+ "/var/log/httpd/httpd").print(num).print("/rewrite.log {\n"
							+ "    missingok\n"
							+ "    daily\n"
							+ "    rotate 379\n"
							+ "}\n"
							+ "/var/log/httpd/httpd").print(num).print("/mod_jk.log {\n"
							+ "    missingok\n"
							+ "    daily\n"
							+ "    rotate 379\n"
							+ "}\n"
							+ "/var/log/httpd/httpd").print(num).print("/ssl_engine_log {\n"
							+ "    missingok\n"
							+ "    daily\n"
							+ "    rotate 379\n"
							+ "}\n");
					if(isFirst) {
						out.print("/var/log/httpd").print(num).print("/suexec.log {\n"
								+ "    missingok\n"
								+ "    daily\n"
								+ "    rotate 379\n"
								+ "}\n");
						isFirst = false;
					}
				}
				DaemonFileUtils.writeIfNeeded(
					byteOut.toByteArray(),
					null,
					new UnixFile(serverLogRotationDir+"/"+filename),
					UnixFile.ROOT_UID,
					UnixFile.ROOT_GID,
					0600,
					uid_min,
					gid_min
				);
			}

			// Remove extra filenames
			for(String extraFilename : logRotationFiles) {
				deleteFileList.add(new File(serverLogRotationDir, extraFilename));
			}
		}
	}

	private HttpdLogManager() {
	}
}
