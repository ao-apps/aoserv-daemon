/*
 * Copyright 2008-2013, 2014, 2015, 2016, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.linux.GroupServer;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.linux.UserServer;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.client.web.Site;
import com.aoindustries.aoserv.client.web.tomcat.SharedTomcat;
import com.aoindustries.aoserv.client.web.tomcat.SharedTomcatSite;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.unix.linux.LinuxAccountManager;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.SortedArrayList;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages SharedTomcat version 3.X configurations.
 * 
 * TODO: Replace all uses of "replace" with a read file then call replace only if one of the "from" values is found.  Should be faster
 *       be eliminating unnecessary subprocesses.
 *
 * @author  AO Industries, Inc.
 */
abstract class HttpdSharedTomcatManager_3_X<TC extends TomcatCommon_3_X> extends HttpdSharedTomcatManager<TC> {

	private static final Logger logger = Logger.getLogger(HttpdSharedTomcatManager_3_X.class.getName());

	HttpdSharedTomcatManager_3_X(SharedTomcat sharedTomcat) {
		super(sharedTomcat);
	}

	@Override
	void buildSharedTomcatDirectory(String optSlash, UnixFile sharedTomcatDirectory, List<File> deleteFileList, Set<SharedTomcat> sharedTomcatsNeedingRestarted) throws IOException, SQLException {
		/*
		 * Get values used in the rest of the loop.
		 */
		final OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
		final HttpdOperatingSystemConfiguration httpdConfig = osConfig.getHttpdOperatingSystemConfiguration();
		final Server thisAoServer = AOServDaemon.getThisAOServer();
		int uid_min = thisAoServer.getUidMin().getId();
		int gid_min = thisAoServer.getGidMin().getId();
		final String optDir = getOptDir();
		final TC tomcatCommon = getTomcatCommon();
		final UserServer lsa = sharedTomcat.getLinuxServerAccount();
		final int lsaUID = lsa.getUid().getId();
		final GroupServer lsg = sharedTomcat.getLinuxServerGroup();
		final int lsgGID = lsg.getGid().getId();
		final String wwwGroupDir = sharedTomcatDirectory.getPath();
		final UnixPath wwwDirectory = httpdConfig.getHttpdSitesDirectory();
		final UnixFile daemonUF = new UnixFile(sharedTomcatDirectory, "daemon", false);
		// Create and fill in the directory if it does not exist or is owned by root.
		final UnixFile workUF = new UnixFile(sharedTomcatDirectory, "work", false);

		boolean needRestart=false;
		Stat sharedTomcatStat = sharedTomcatDirectory.getStat();
		if (!sharedTomcatStat.exists() || sharedTomcatStat.getUid() == UnixFile.ROOT_GID) {

			// Create the /wwwgroup/name/...

			// 001
			if (!sharedTomcatStat.exists()) sharedTomcatDirectory.mkdir();
			sharedTomcatDirectory.setMode(0770);
			new UnixFile(sharedTomcatDirectory, "bin", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
			new UnixFile(sharedTomcatDirectory, "conf", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
			daemonUF.mkdir().chown(lsaUID, lsgGID).setMode(0770);
			UnixFile varUF = new UnixFile(sharedTomcatDirectory, "var", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
			new UnixFile(varUF, "log", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
			new UnixFile(varUF, "run", false).mkdir().chown(lsaUID, lsgGID).setMode(0700);

			workUF.mkdir().chown(lsaUID, lsgGID).setMode(0750);

			//Server postgresServer=aoServer.getPreferredPostgresServer();
			//String postgresServerMinorVersion=postgresServer==null?null:postgresServer.getPostgresVersion().getMinorVersion();

			String profileFile = wwwGroupDir + "/bin/profile";
			LinuxAccountManager.setBashProfile(lsa, profileFile);

			UnixFile profileUF = new UnixFile(profileFile);
			ChainWriter out = new ChainWriter(
				new BufferedOutputStream(
					profileUF.getSecureOutputStream(lsaUID, lsgGID, 0750, false, uid_min, gid_min)
				)
			);
			try {
				out.print("#!/bin/sh\n"
						+ "\n"
						+ ". /etc/profile\n"
						+ ". ").print(osConfig.getJdk17ProfileSh()).print('\n'
						+ ". /opt/jakarta-oro-2.0/setenv.sh\n"
						+ ". /opt/jakarta-regexp-1/setenv.sh\n"
						//+ ". /opt/jakarta-servletapi-").print(tomcatCommon.getServletApiVersion()).print("/setenv.sh\n"
						+ ". /opt/apache-tomcat-").print(tomcatCommon.getTomcatApiVersion()).print("/setenv.sh\n"
						+ ". /opt/jetspeed-1.1/setenv.sh\n"
						+ ". /opt/cocoon-1.8/setenv.sh\n"
						+ ". /opt/xerces-1.2/setenv.sh\n"
						+ ". /opt/ant-1/setenv.sh\n"
						+ ". /opt/xalan-1.2/setenv.sh\n");
				//if(postgresServerMinorVersion!=null) {
				//	out.print(". /opt/postgresql-"+postgresServerMinorVersion+"-i686/setenv.sh\n");
				//}
				out.print(". /opt/castor-0.8/setenv.sh\n"
						+ ". /opt/cos-27May2002/setenv.sh\n"
						+ ". /opt/ecs-1.3/setenv.sh\n"
						+ ". /opt/freemarker-1.5/setenv.sh\n"
						+ ". /opt/gnu.regexp-1.0/setenv.sh\n"
						+ ". /opt/jaf-1.0/setenv.sh\n"
						+ ". /opt/slide-1.0/setenv.sh\n"
						+ ". /opt/kavachart-3.1/setenv.sh\n"
						+ ". /opt/javamail-1.1/setenv.sh\n"
						+ ". /opt/jdbc-2.0/setenv.sh\n"
						+ ". /opt/jsse-1.0/setenv.sh\n"
						+ ". /opt/jyve-20000907/setenv.sh\n"
						+ ". /opt/mm.mysql-2.0/setenv.sh\n"
						+ ". /opt/openxml-1.2/setenv.sh\n"
						+ ". /opt/pop3-1.1/setenv.sh\n"
						+ ". /opt/soap-2.0/setenv.sh\n"
						+ ". /opt/spfc-0.2/setenv.sh\n"
						+ ". /opt/turbine-20000907/setenv.sh\n"
						+ ". /opt/village-1.3/setenv.sh\n"
						+ ". /opt/webmacro-27-08-2000/setenv.sh\n"
						+ ". /opt/xang-0.0/setenv.sh\n"
						+ ". /opt/xmlrpc-1.0/setenv.sh\n"
						+ ". /opt/poolman-1.4/setenv.sh\n"
						+ "\n"
						+ "export PATH=\"${PATH}:").print(wwwGroupDir).print("/bin\"\n"
						+ "\n"
						+ "export JAVA_OPTS='-server -Djava.awt.headless=true -Xmx128M -Djdk.disableLastUsageTracking=true'\n"
						+ "\n"
						+ "# Add site group classes\n"
						+ "CLASSPATH=\"${CLASSPATH}:").print(wwwGroupDir).print("/classes\"\n"
						+ "for i in ").print(wwwGroupDir).print("/lib/* ; do\n"
						+ "    if [ -f \"$i\" ]; then\n"
						+ "        CLASSPATH=\"${CLASSPATH}:$i\"\n"
						+ "    fi\n"
						+ "done\n"
						+ "\n"
						+ ". ").print(wwwGroupDir).print("/bin/profile.sites\n"
						+ "\n"
						+ "for SITE in $SITES\n"
						+ "do\n"
						+ "    export PATH=\"${PATH}:").print(wwwDirectory).print("/${SITE}/bin\"\n"
						+ "    CLASSPATH=\"${CLASSPATH}:").print(wwwDirectory).print("/${SITE}/classes\"\n"
						+ "\n"
						+ "    for i in ").print(wwwDirectory).print("/${SITE}/lib/* ; do\n"
						+ "        if [ -f \"$i\" ]; then\n"
						+ "            CLASSPATH=\"${CLASSPATH}:$i\"\n"
						+ "        fi\n"
						+ "    done\n"
						+ "done\n"
						+ "export CLASSPATH\n");
			} finally {
				out.close();
			}

			// 004

			UnixFile tomcatUF = new UnixFile(wwwGroupDir + "/bin/tomcat");
			out=new ChainWriter(
				new BufferedOutputStream(
					tomcatUF.getSecureOutputStream(lsaUID, lsgGID, 0700, false, uid_min, gid_min)
				)
			);
			try {
				out.print("#!/bin/sh\n"
						+ "\n"
						+ "TOMCAT_HOME=\"").print(wwwGroupDir).print("\"\n"
						+ "\n"
						+ "if [ \"$1\" = \"start\" ]; then\n"
						+ "    \"$0\" stop\n"
						+ "    \"$0\" daemon &\n"
						+ "    echo \"$!\" >\"${TOMCAT_HOME}/var/run/tomcat.pid\"\n"
						+ "elif [ \"$1\" = \"stop\" ]; then\n"
						+ "    if [ -f \"${TOMCAT_HOME}/var/run/tomcat.pid\" ]; then\n"
						+ "        kill `cat \"${TOMCAT_HOME}/var/run/tomcat.pid\"`\n"
						+ "        rm -f \"${TOMCAT_HOME}/var/run/tomcat.pid\"\n"
						+ "    fi\n"
						+ "    if [ -f \"${TOMCAT_HOME}/var/run/java.pid\" ]; then\n"
						+ "        . \"$TOMCAT_HOME/bin/profile\"\n"
						+ "        umask 002\n"
						//+ "        ulimit -S -m 196608 -v 400000\n"
						//+ "        ulimit -H -m 196608 -v 400000\n"
						+ "        if [ \"$SITES\" != \"\" ]; then\n"
						+ "            cd \"$TOMCAT_HOME\"\n"
						+ "            java com.aoindustries.apache.tomcat.VirtualTomcat stop $SITES &>/dev/null\n"
						+ "        fi\n"
						+ "        kill `cat \"${TOMCAT_HOME}/var/run/java.pid\"` &>/dev/null\n"
						+ "        rm -f \"${TOMCAT_HOME}/var/run/java.pid\"\n"
						+ "    fi\n"
						+ "elif [ \"$1\" = \"daemon\" ]; then\n"
						+ "    cd \"$TOMCAT_HOME\"\n"
						+ "    . \"$TOMCAT_HOME/bin/profile\"\n"
						+ "\n"
						+ "    if [ \"$SITES\" != \"\" ]; then\n"
						+ "        while [ 1 ]; do\n"
						//+ "            ulimit -S -m 196608 -v 400000\n"
						//+ "            ulimit -H -m 196608 -v 400000\n"
						+ "            umask 002\n"
						+ "            java com.aoindustries.apache.tomcat.VirtualTomcat start $SITES &>var/log/servlet_err &\n"
						+ "            echo \"$!\" >\"${TOMCAT_HOME}/var/run/java.pid\"\n"
						+ "            wait\n"
						+ "            RETCODE=\"$?\"\n"
						+ "            echo \"`date`: JVM died with a return code of $RETCODE, restarting in 5 seconds\" >>\"${TOMCAT_HOME}/var/log/jvm_crashes.log\"\n"
						+ "            sleep 5\n"
						+ "        done\n"
						+ "    fi\n"
						+ "    rm -f \"${TOMCAT_HOME}/var/run/tomcat.pid\"\n"
						+ "else\n"
						+ "    echo \"Usage:\"\n"
						+ "    echo \"tomcat {start|stop}\"\n"
						+ "    echo \"        start - start tomcat\"\n"
						+ "    echo \"        stop  - stop tomcat\"\n"
						+ "fi\n"
				);
			} finally {
				out.close();
			}

			// The classes directory
			new UnixFile(sharedTomcatDirectory, "classes", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);

			// Create /lib
			new UnixFile(sharedTomcatDirectory, "lib", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
			DaemonFileUtils.lnAll("../" + optSlash + optDir + "/lib/", wwwGroupDir+"/lib/", lsaUID, lsgGID);
			DaemonFileUtils.ln("../" + optSlash + optDir + "/lib/jasper-runtime.jar", wwwGroupDir+"/lib/jasper-runtime.jar", lsaUID, lsgGID);
			//if(postgresServerMinorVersion!=null) {
			//    String postgresPath = osConfig.getPostgresPath(postgresServerMinorVersion);
			//    if(postgresPath!=null) FileUtils.ln("../../.."+postgresPath+"/share/java/postgresql.jar", wwwGroupDir+"/lib/postgresql.jar", lsaUID, lsgGID);
			//}
			//String mysqlConnectorPath = osConfig.getMySQLConnectorJavaJarPath();
			//if(mysqlConnectorPath!=null) {
			//    String filename = new UnixFile(mysqlConnectorPath).getFile().getName();
			//    FileUtils.ln("../../.."+mysqlConnectorPath, wwwGroupDir+"/lib/"+filename, lsaUID, lsgGID);
			//}
			UnixFile servErrUF = new UnixFile(varUF, "log/servlet_err", false);
			servErrUF.getSecureOutputStream(lsaUID, lsgGID, 0640, false, uid_min, gid_min).close();

			// Set the ownership to avoid future rebuilds of this directory
			sharedTomcatDirectory.chown(lsaUID, lsgGID);

			needRestart=true;
		}

		// always rebuild profile.sites file
		UnixFile newSitesFileUF = new UnixFile(sharedTomcatDirectory, "bin/profile.sites.new", false);
		ChainWriter out = new ChainWriter(
			new BufferedOutputStream(
				newSitesFileUF.getSecureOutputStream(lsaUID, lsgGID, 0750, true, uid_min, gid_min)
			)
		);
		List<SharedTomcatSite> sites = sharedTomcat.getHttpdTomcatSharedSites();
		try {
			out.print("export SITES=\"");
			boolean didOne=false;
			for(SharedTomcatSite site : sites) {
				Site hs = site.getHttpdTomcatSite().getHttpdSite();
				if(!hs.isDisabled()) {
					if(didOne) out.print(' ');
					else didOne=true;
					out.print(hs.getName());
				}
			}
			out.print("\"\n");
		} finally {
			out.close();
		}
		// flag as needing a restart if this file is different than any existing
		UnixFile sitesFile = new UnixFile(sharedTomcatDirectory, "bin/profile.sites", false);
		Stat sitesStat = sitesFile.getStat();
		if(!sitesStat.exists() || !newSitesFileUF.contentEquals(sitesFile)) {
			needRestart=true;
			if(sitesStat.exists()) {
				UnixFile backupFile=new UnixFile(sharedTomcatDirectory, "bin/profile.sites.old", false);
				sitesFile.renameTo(backupFile);
			}
			newSitesFileUF.renameTo(sitesFile);
		} else newSitesFileUF.delete();

		// make work directories and remove extra work dirs
		List<String> workFiles = new SortedArrayList<>();
		String[] wlist = workUF.getFile().list();
		if(wlist!=null) workFiles.addAll(Arrays.asList(wlist));
		for (SharedTomcatSite site : sites) {
			Site hs = site.getHttpdTomcatSite().getHttpdSite();
			if(!hs.isDisabled()) {
				String subwork = hs.getName();
				workFiles.remove(subwork);
				UnixFile workDir = new UnixFile(workUF, subwork, false);
				if (!workDir.getStat().exists()) {
					workDir
						.mkdir()
						.chown(
							lsaUID, 
							hs.getLinuxServerGroup().getGid().getId()
						)
						.setMode(0750)
					;
				}
			}
		}
		for(String workFile : workFiles) {
			File toDelete = new File(workUF.getFile(), workFile);
			if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + toDelete);
			deleteFileList.add(toDelete);
		}

		// Enable/Disable
		boolean hasEnabledSite = false;
		for(SharedTomcatSite htss : sharedTomcat.getHttpdTomcatSharedSites()) {
			if(!htss.getHttpdTomcatSite().getHttpdSite().isDisabled()) {
				hasEnabledSite = true;
				break;
			}
		}
		UnixFile daemonSymlink = new UnixFile(daemonUF, "tomcat", false);
		if(!sharedTomcat.isDisabled() && hasEnabledSite) {
			// Enabled
			if(!daemonSymlink.getStat().exists()) {
				daemonSymlink
					.symLink("../bin/tomcat")
					.chown(lsaUID, lsgGID);
			}
			// Start if needed
			if(needRestart) sharedTomcatsNeedingRestarted.add(sharedTomcat);
		} else {
			// Disabled
			if(daemonSymlink.getStat().exists()) daemonSymlink.delete();
		}
	}

	@Override
	protected boolean upgradeSharedTomcatDirectory(String optSlash, UnixFile siteDirectory) throws IOException, SQLException {
		// Nothing to do
		return false;
	}

	/**
	 * Gets the package's directory name under /opt, not including /opt itself.
	 */
	abstract protected String getOptDir();
}
