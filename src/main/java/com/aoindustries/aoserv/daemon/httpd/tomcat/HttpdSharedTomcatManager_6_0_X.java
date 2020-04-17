/*
 * Copyright 2008-2013, 2014, 2015, 2016, 2017, 2018, 2019, 2020 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.linux.GroupServer;
import com.aoindustries.aoserv.client.linux.PosixPath;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.linux.UserServer;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.client.net.IpAddress;
import com.aoindustries.aoserv.client.web.VirtualHost;
import com.aoindustries.aoserv.client.web.VirtualHostName;
import com.aoindustries.aoserv.client.web.tomcat.Context;
import com.aoindustries.aoserv.client.web.tomcat.ContextDataSource;
import com.aoindustries.aoserv.client.web.tomcat.ContextParameter;
import com.aoindustries.aoserv.client.web.tomcat.SharedTomcat;
import com.aoindustries.aoserv.client.web.tomcat.SharedTomcatSite;
import com.aoindustries.aoserv.client.web.tomcat.Site;
import com.aoindustries.aoserv.client.web.tomcat.Worker;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.unix.linux.LinuxAccountManager;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.collections.SortedArrayList;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.net.DomainName;
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
 * Manages SharedTomcat version 6.0.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdSharedTomcatManager_6_0_X extends HttpdSharedTomcatManager<TomcatCommon_6_0_X> {

	private static final Logger logger = Logger.getLogger(HttpdSharedTomcatManager_6_0_X.class.getName());

	HttpdSharedTomcatManager_6_0_X(SharedTomcat sharedTomcat) {
		super(sharedTomcat);
	}

	@Override
	TomcatCommon_6_0_X getTomcatCommon() {
		return TomcatCommon_6_0_X.getInstance();
	}

	@Override
	void buildSharedTomcatDirectory(String optSlash, UnixFile sharedTomcatDirectory, List<File> deleteFileList, Set<SharedTomcat> sharedTomcatsNeedingRestarted) throws IOException, SQLException {
		/*
		 * Get values used in the rest of the loop.
		 */
		final OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
		final HttpdOperatingSystemConfiguration httpdConfig = osConfig.getHttpdOperatingSystemConfiguration();
		final Server thisServer = AOServDaemon.getThisServer();
		int uid_min = thisServer.getUidMin().getId();
		int gid_min = thisServer.getGidMin().getId();
		final TomcatCommon_6_0_X tomcatCommon = getTomcatCommon();
		final UserServer lsa = sharedTomcat.getLinuxServerAccount();
		final int lsaUID = lsa.getUid().getId();
		final GroupServer lsg = sharedTomcat.getLinuxServerGroup();
		final int lsgGID = lsg.getGid().getId();
		final String wwwGroupDir = sharedTomcatDirectory.getPath();
		final PosixPath wwwDirectory = httpdConfig.getHttpdSitesDirectory();
		final UnixFile daemonUF = new UnixFile(sharedTomcatDirectory, "daemon", false);
		final UnixFile confUF = new UnixFile(sharedTomcatDirectory, "conf", false);

		// Create and fill in the directory if it does not exist or is owned by root.
		UnixFile workUF = new UnixFile(sharedTomcatDirectory, "work", false);
		UnixFile innerWorkUF = new UnixFile(workUF, "Catalina", false);

		boolean needRestart=false;
		Stat sharedTomcatStat = sharedTomcatDirectory.getStat();
		if (!sharedTomcatStat.exists() || sharedTomcatStat.getUid() == UnixFile.ROOT_GID) {

			// Create the /wwwgroup/name/...

			// 001
			if (!sharedTomcatStat.exists()) sharedTomcatDirectory.mkdir();
			sharedTomcatDirectory.setMode(0770);
			new UnixFile(sharedTomcatDirectory, "bin", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
			confUF.mkdir().chown(lsaUID, lsgGID).setMode(0770);
			new UnixFile(confUF, "Catalina", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
			daemonUF.mkdir().chown(lsaUID, lsgGID).setMode(0770);
			DaemonFileUtils.ln("var/log", wwwGroupDir+"/logs", lsaUID, lsgGID);
			DaemonFileUtils.mkdir(wwwGroupDir+"/temp", 0770, lsaUID, lsgGID);
			UnixFile varUF = new UnixFile(sharedTomcatDirectory, "var", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
			new UnixFile(varUF, "log", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
			new UnixFile(varUF, "run", false).mkdir().chown(lsaUID, lsgGID).setMode(0700);

			workUF.mkdir().chown(lsaUID, lsgGID).setMode(0750);
			DaemonFileUtils.mkdir(innerWorkUF.getPath(), 0750, lsaUID, lsgGID);

			DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-6.0/bin/bootstrap.jar", wwwGroupDir+"/bin/bootstrap.jar", lsaUID, lsgGID);
			DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-6.0/bin/catalina.sh", wwwGroupDir+"/bin/catalina.sh", lsaUID, lsgGID);
			DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-6.0/bin/commons-daemon.jar", wwwGroupDir+"/bin/commons-daemon.jar", lsaUID, lsgGID);
			DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-6.0/bin/digest.sh", wwwGroupDir+"/bin/digest.sh", lsaUID, lsgGID);

			String profileFile = wwwGroupDir + "/bin/profile";
			LinuxAccountManager.setBashProfile(lsa, profileFile);

			UnixFile profileUF = new UnixFile(profileFile);
			try (
				ChainWriter out = new ChainWriter(
					new BufferedOutputStream(
						profileUF.getSecureOutputStream(lsaUID, lsgGID, 0750, false, uid_min, gid_min)
					)
				)
			) {
				out.print("#!/bin/sh\n"
						  + "\n");

				out.print(". /etc/profile\n"
						+ ". ").print(osConfig.getDefaultJdkProfileSh()).print('\n');
				out.print("\n"
						+ "umask 002\n"
						+ "\n"
						+ "export CATALINA_BASE=\"").print(wwwGroupDir).print("\"\n"
						+ "export CATALINA_HOME=\"").print(wwwGroupDir).print("\"\n"
						+ "export CATALINA_TEMP=\"").print(wwwGroupDir).print("/temp\"\n"
				);
				out.print("\n"
						+ "export PATH=\"${PATH}:").print(wwwGroupDir).print("/bin\"\n"
						+ "\n"
						+ "export JAVA_OPTS='-server -Djava.awt.headless=true -Xmx128M -Djdk.disableLastUsageTracking=true'\n"
						+ "\n");
				out.print(". ").print(wwwGroupDir).print("/bin/profile.sites\n"
						+ "\n"
						+ "for SITE in $SITES\n"
						+ "do\n"
						+ "    export PATH=\"${PATH}:").print(wwwDirectory).print("/${SITE}/bin\"\n"
						+ "done\n");
			}

			UnixFile tomcatUF = new UnixFile(wwwGroupDir + "/bin/tomcat");
			try (
				ChainWriter out = new ChainWriter(
					new BufferedOutputStream(
						tomcatUF.getSecureOutputStream(lsaUID, lsgGID, 0700, false, uid_min, gid_min)
					)
				)
			) {
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
						+ "        if [ \"$SITES\" != \"\" ]; then\n"
						+ "            cd \"$TOMCAT_HOME\"\n"
						+ "            \"${TOMCAT_HOME}/bin/catalina.sh\" stop 2>&1 >>\"${TOMCAT_HOME}/var/log/tomcat_err\"\n"
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
						+ "            mv -f \"${TOMCAT_HOME}/var/log/tomcat_err\" \"${TOMCAT_HOME}/var/log/tomcat_err_$(date +%Y%m%d_%H%M%S)\"\n"
						+ "            \"${TOMCAT_HOME}/bin/catalina.sh\" run >&\"${TOMCAT_HOME}/var/log/tomcat_err\" &\n"
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
			}

			DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-6.0/bin/setclasspath.sh", wwwGroupDir+"/bin/setclasspath.sh", lsaUID, lsgGID);

			UnixFile shutdown=new UnixFile(wwwGroupDir+"/bin/shutdown.sh");
			try (ChainWriter out = new ChainWriter(shutdown.getSecureOutputStream(lsaUID, lsgGID, 0700, true, uid_min, gid_min))) {
				out.print("#!/bin/sh\n"
						  + "exec \"").print(wwwGroupDir).print("/bin/tomcat\" stop\n");
			}

			UnixFile startup=new UnixFile(wwwGroupDir+"/bin/startup.sh");
			try (ChainWriter out = new ChainWriter(startup.getSecureOutputStream(lsaUID, lsgGID, 0700, true, uid_min, gid_min))) {
				out.print("#!/bin/sh\n"
						  + "exec \"").print(wwwGroupDir).print("/bin/tomcat\" start\n");
			}

			DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-6.0/bin/tomcat-juli.jar", wwwGroupDir+"/bin/tomcat-juli.jar", lsaUID, lsgGID);
			DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-6.0/bin/tool-wrapper.sh", wwwGroupDir+"/bin/tool-wrapper.sh", lsaUID, lsgGID);
			DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-6.0/bin/version.sh", wwwGroupDir+"/bin/version.sh", lsaUID, lsgGID);

			// Create the lib directory and all contents
			DaemonFileUtils.mkdir(wwwGroupDir+"/lib", 0770, lsaUID, lsgGID);
			DaemonFileUtils.lnAll("../" + optSlash + "apache-tomcat-6.0/lib/", wwwGroupDir+"/lib/", lsaUID, lsgGID);

			{
				UnixFile cp=new UnixFile(wwwGroupDir+"/conf/catalina.policy");
				new UnixFile("/opt/apache-tomcat-6.0/conf/catalina.policy").copyTo(cp, false);
				cp.chown(lsaUID, lsgGID).setMode(0660);
			}
			{
				UnixFile cp=new UnixFile(wwwGroupDir+"/conf/catalina.properties");
				new UnixFile("/opt/apache-tomcat-6.0/conf/catalina.properties").copyTo(cp, false);
				cp.chown(lsaUID, lsgGID).setMode(0660);
			}
			{
				UnixFile cp=new UnixFile(wwwGroupDir+"/conf/context.xml");
				new UnixFile("/opt/apache-tomcat-6.0/conf/context.xml").copyTo(cp, false);
				cp.chown(lsaUID, lsgGID).setMode(0660);
			}
			{
				UnixFile cp=new UnixFile(wwwGroupDir+"/conf/logging.properties");
				new UnixFile("/opt/apache-tomcat-6.0/conf/logging.properties").copyTo(cp, false);
				cp.chown(lsaUID, lsgGID).setMode(0660);
			}
			{
				UnixFile tuUF=new UnixFile(wwwGroupDir+"/conf/tomcat-users.xml");
				new UnixFile("/opt/apache-tomcat-6.0/conf/tomcat-users.xml").copyTo(tuUF, false);
				tuUF.chown(lsaUID, lsgGID).setMode(0660);
			}
			{
				UnixFile webUF=new UnixFile(wwwGroupDir+"/conf/web.xml");
				new UnixFile("/opt/apache-tomcat-6.0/conf/web.xml").copyTo(webUF, false);
				webUF.chown(lsaUID, lsgGID).setMode(0660);
			}

			// Set the ownership to avoid future rebuilds of this directory
			sharedTomcatDirectory.chown(lsaUID, lsgGID);

			needRestart=true;
		}

		// always rebuild profile.sites file
		List<SharedTomcatSite> sites = sharedTomcat.getHttpdTomcatSharedSites();
		UnixFile newSitesFileUF = new UnixFile(sharedTomcatDirectory, "bin/profile.sites.new", false);
		try (
			ChainWriter out = new ChainWriter(
				new BufferedOutputStream(
					newSitesFileUF.getSecureOutputStream(lsaUID, lsgGID, 0750, true, uid_min, gid_min)
				)
			)
		) {
			out.print("export SITES=\"");
			boolean didOne=false;
			for(SharedTomcatSite site : sites) {
				com.aoindustries.aoserv.client.web.Site hs = site.getHttpdTomcatSite().getHttpdSite();
				if(!hs.isDisabled()) {
					if(didOne) out.print(' ');
					else didOne=true;
					out.print(hs.getName());
				}
			}
			out.print("\"\n");
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
		String[] wlist = innerWorkUF.getFile().list();
		if(wlist!=null) {
			workFiles.addAll(Arrays.asList(wlist));
		}
		for (SharedTomcatSite site : sites) {
			com.aoindustries.aoserv.client.web.Site hs = site.getHttpdTomcatSite().getHttpdSite();
			if (!hs.isDisabled()) {
				String subwork = hs.getPrimaryHttpdSiteURL().getHostname().toString();
				workFiles.remove(subwork);
				UnixFile workDir = new UnixFile(innerWorkUF, subwork, false);
				if (!workDir.getStat().exists()) {
					workDir
						.mkdir().chown(lsaUID, site.getHttpdTomcatSite().getHttpdSite().getLinuxServerGroup().getGid().getId()).setMode(0750);
				}
			}
		}
		for(String workFile : workFiles) {
			File toDelete = new File(innerWorkUF.getFile(), workFile);
			if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + toDelete);
			deleteFileList.add(toDelete);
		}

		// Rebuild the server.xml
		String autoWarning = getAutoWarningXml();
		String autoWarningOld = getAutoWarningXmlOld();
		String confServerXML=wwwGroupDir+"/conf/server.xml";
		UnixFile confServerXMLUF=new UnixFile(confServerXML);
		if(!sharedTomcat.isManual() || !confServerXMLUF.getStat().exists()) {
			String newConfServerXML=wwwGroupDir+"/conf/server.xml.new";
			UnixFile newConfServerXMLUF=new UnixFile(newConfServerXML);
			try (
				ChainWriter out = new ChainWriter(
					new BufferedOutputStream(
						newConfServerXMLUF.getSecureOutputStream(lsaUID, lsgGID, 0660, true, uid_min, gid_min)
					)
				)
			) {
				Worker hw=sharedTomcat.getTomcat4Worker();
				if(!sharedTomcat.isManual()) out.print(autoWarning);
				Bind shutdownPort = sharedTomcat.getTomcat4ShutdownPort();
				if(shutdownPort==null) throw new SQLException("Unable to find shutdown key for SharedTomcat: "+sharedTomcat);
				String shutdownKey=sharedTomcat.getTomcat4ShutdownKey();
				if(shutdownKey==null) throw new SQLException("Unable to find shutdown key for SharedTomcat: "+sharedTomcat);
				out.print("<Server port=\"").textInXmlAttribute(shutdownPort.getPort().getPort()).print("\" shutdown=\"").textInXmlAttribute(shutdownKey).print("\">\n"
						+ "  <Listener className=\"org.apache.catalina.core.AprLifecycleListener\" SSLEngine=\"on\" />\n"
						+ "  <Listener className=\"org.apache.catalina.core.JasperListener\" />\n"
						+ "  <Listener className=\"org.apache.catalina.mbeans.ServerLifecycleListener\" />\n"
						+ "  <Listener className=\"org.apache.catalina.mbeans.GlobalResourcesLifecycleListener\" />\n"
						+ "  <GlobalNamingResources>\n"
						+ "    <Resource name=\"UserDatabase\" auth=\"Container\"\n"
						+ "              type=\"org.apache.catalina.UserDatabase\"\n"
						+ "              description=\"User database that can be updated and saved\"\n"
						+ "              factory=\"org.apache.catalina.users.MemoryUserDatabaseFactory\"\n"
						+ "              pathname=\"conf/tomcat-users.xml\" />\n"
						+ "  </GlobalNamingResources>\n"
						+ "  <Service name=\"Catalina\">\n"
						+ "    <Connector\n"
						+ "      port=\"").textInXmlAttribute(hw.getBind().getPort().getPort()).print("\"\n"
						+ "      address=\"").textInXmlAttribute(IpAddress.LOOPBACK_IP).print("\"\n"
						+ "      maxPostSize=\"").textInXmlAttribute(sharedTomcat.getMaxPostSize()).print("\"\n"
						+ "      protocol=\"AJP/1.3\"\n"
						+ "      redirectPort=\"8443\"\n");
				// Do not include when is default "true"
				if(!sharedTomcat.getTomcatAuthentication()) {
					out.print("      tomcatAuthentication=\"false\"\n");
				}
				out.print("    />\n"
						+ "    <Engine name=\"Catalina\" defaultHost=\"localhost\">\n"
						+ "      <Realm className=\"org.apache.catalina.realm.UserDatabaseRealm\"\n"
						+ "             resourceName=\"UserDatabase\" />\"\n");
				for (SharedTomcatSite site : sites) {
					com.aoindustries.aoserv.client.web.Site hs = site.getHttpdTomcatSite().getHttpdSite();
					if(!hs.isDisabled()) {
						DomainName primaryHostname=hs.getPrimaryHttpdSiteURL().getHostname();
						out.print("      <Host\n"
								+ "        name=\"").textInXmlAttribute(primaryHostname.toString()).print("\"\n"
								+ "        appBase=\"").textInXmlAttribute(wwwDirectory).print('/').textInXmlAttribute(hs.getName()).print("/webapps\"\n"
								+ "        unpackWARs=\"").textInXmlAttribute(sharedTomcat.getUnpackWARs()).print("\"\n"
								+ "        autoDeploy=\"").textInXmlAttribute(sharedTomcat.getAutoDeploy()).print("\"\n"
								+ "        xmlValidation=\"false\"\n"
								+ "        xmlNamespaceAware=\"false\"\n"
								+ "      >\n");
						List<String> usedHostnames=new SortedArrayList<>();
						usedHostnames.add(primaryHostname.toString());
						List<VirtualHost> binds=hs.getHttpdSiteBinds();
						for (VirtualHost bind : binds) {
							List<VirtualHostName> urls=bind.getHttpdSiteURLs();
							for (VirtualHostName url : urls) {
								DomainName hostname = url.getHostname();
								if(!usedHostnames.contains(hostname.toString())) {
									out.print("        <Alias>").textInXhtml(hostname).print("</Alias>\n");
									usedHostnames.add(hostname.toString());
								}
							}
							// When listed first, also include the IP addresses as aliases
							if(hs.getListFirst()) {
								String ip=bind.getHttpdBind().getNetBind().getIpAddress().getInetAddress().toString();
								if(!usedHostnames.contains(ip)) {
									out.print("        <Alias>").textInXhtml(ip).print("</Alias>\n");
									usedHostnames.add(ip);
								}
							}
						}
						Site tomcatSite=hs.getHttpdTomcatSite();
						for(Context htc : tomcatSite.getHttpdTomcatContexts()) {
							if(!htc.isServerXmlConfigured()) out.print("        <!--\n");
							out.print("        <Context\n");
							if(htc.getClassName()!=null) out.print("          className=\"").textInXmlAttribute(htc.getClassName()).print("\"\n");
							out.print("          cookies=\"").textInXmlAttribute(htc.useCookies()).print("\"\n"
									+ "          crossContext=\"").textInXmlAttribute(htc.allowCrossContext()).print("\"\n"
									+ "          docBase=\"").textInXmlAttribute(htc.getDocBase()).print("\"\n"
									+ "          override=\"").textInXmlAttribute(htc.allowOverride()).print("\"\n"
									+ "          path=\"").textInXmlAttribute(htc.getPath()).print("\"\n"
									+ "          privileged=\"").textInXmlAttribute(htc.isPrivileged()).print("\"\n"
									+ "          reloadable=\"").textInXmlAttribute(htc.isReloadable()).print("\"\n"
									+ "          useNaming=\"").textInXmlAttribute(htc.useNaming()).print("\"\n");
							if(htc.getWrapperClass()!=null) out.print("          wrapperClass=\"").textInXmlAttribute(htc.getWrapperClass()).print("\"\n");
							out.print("          debug=\"").textInXmlAttribute(htc.getDebugLevel()).print("\"\n");
							if(htc.getWorkDir()!=null) out.print("          workDir=\"").textInXmlAttribute(htc.getWorkDir()).print("\"\n");
							List<ContextParameter> parameters=htc.getHttpdTomcatParameters();
							List<ContextDataSource> dataSources=htc.getHttpdTomcatDataSources();
							if(parameters.isEmpty() && dataSources.isEmpty()) {
								out.print("        />\n");
							} else {
								out.print("        >\n");
								// Parameters
								for(ContextParameter parameter : parameters) {
									tomcatCommon.writeHttpdTomcatParameter(parameter, out);
								}
								// Data Sources
								for(ContextDataSource dataSource : dataSources) {
									tomcatCommon.writeHttpdTomcatDataSource(dataSource, out);
								}
								out.print("        </Context>\n");
							}
							if(!htc.isServerXmlConfigured()) out.print("        -->\n");
						}
						out.print("      </Host>\n");
					}
				}
				out.print("    </Engine>\n"
						+ "  </Service>\n"
						+ "</Server>\n");
			}

			// Must restart JVM if this file has changed
			if(
				!confServerXMLUF.getStat().exists()
				|| !newConfServerXMLUF.contentEquals(confServerXMLUF)
			) {
				needRestart=true;
				newConfServerXMLUF.renameTo(confServerXMLUF);
			} else newConfServerXMLUF.delete();
		} else {
			try {
				DaemonFileUtils.stripFilePrefix(
					confServerXMLUF,
					autoWarningOld,
					uid_min,
					gid_min
				);
				DaemonFileUtils.stripFilePrefix(
					confServerXMLUF,
					autoWarning,
					uid_min,
					gid_min
				);
			} catch(IOException err) {
				// Errors OK because this is done in manual mode and they might have symbolic linked stuff
			}
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
		// Upgrade Tomcat
		boolean needsRestart = getTomcatCommon().upgradeTomcatDirectory(
			optSlash,
			siteDirectory,
			sharedTomcat.getLinuxServerAccount().getUid().getId(),
			sharedTomcat.getLinuxServerGroup().getGid().getId()
		);

		// Update bin/tomcat script
		/*
		OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
		if(osConfig==OperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64) {
			// Replace /usr/aoserv/sbin/filtersites in bin/tomcat
			String results = AOServDaemon.execAndCapture(
				new String[] {
					osConfig.getReplaceCommand(),
					"    SITES=`/usr/aoserv/sbin/filtersites", // Leading spaces prevent repetitive updates
					"    # SITES=`/usr/aoserv/sbin/filtersites",

					// Fix upgrade mistake
					"# # SITES=`/usr/aoserv/sbin/filtersites",
					"# SITES=`/usr/aoserv/sbin/filtersites",

					"--",
					siteDirectory.getPath()+"/bin/tomcat"
				}
			);
			if(results.length()>0) needsRestart = true;
		}
		 */
		return needsRestart;
	}
}
