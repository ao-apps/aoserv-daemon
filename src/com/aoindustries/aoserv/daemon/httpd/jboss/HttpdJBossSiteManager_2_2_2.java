/*
 * Copyright 2007-2013, 2014, 2015, 2016, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.jboss;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.HttpdJBossSite;
import com.aoindustries.aoserv.client.HttpdJKProtocol;
import com.aoindustries.aoserv.client.HttpdTomcatContext;
import com.aoindustries.aoserv.client.HttpdTomcatVersion;
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.LinuxServerGroup;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.httpd.tomcat.TomcatCommon_3_2_4;
import com.aoindustries.aoserv.daemon.httpd.tomcat.TomcatCommon_3_X;
import com.aoindustries.aoserv.daemon.unix.linux.LinuxAccountManager;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.net.InetAddress;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Manages HttpdJBossSite version 2.2.2 configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdJBossSiteManager_2_2_2 extends HttpdJBossSiteManager<TomcatCommon_3_2_4> {

	HttpdJBossSiteManager_2_2_2(HttpdJBossSite jbossSite) throws SQLException, IOException {
		super(jbossSite);
	}

	@Override
	protected Set<PackageManager.PackageName> getRequiredPackages() {
		return EnumSet.of(PackageManager.PackageName.JBOSS_2_2_2);
	}

	/**
	 * Builds a JBoss 2.2.2 installation
	 */
	@Override
	protected void buildSiteDirectoryContents(UnixFile siteDirectory) throws IOException, SQLException {
		/*
		 * Resolve and allocate stuff used throughout the method
		 */
		final OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
		final String replaceCommand = osConfig.getReplaceCommand();
		if(replaceCommand==null) throw new IOException("OperatingSystem doesn't have replace command");
		final TomcatCommon_3_2_4 tomcatCommon = getTomcatCommon();
		final String siteDir = siteDirectory.getPath();
		final LinuxServerAccount lsa = httpdSite.getLinuxServerAccount();
		final LinuxServerGroup lsg = httpdSite.getLinuxServerGroup();
		final int uid = lsa.getUid().getId();
		final int gid = lsg.getGid().getId();
		final String laUsername=lsa.getLinuxAccount().getUsername().getUsername();
		final String laGroupname = lsg.getLinuxGroup().getName();
		final String siteName=httpdSite.getSiteName();

		/*
		 * Create the skeleton of the site, the directories and links.
		 */
		DaemonFileUtils.mkdir(new UnixFile(siteDirectory, "bin", false), 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/conf", 0775, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/daemon", 0770, uid, gid);
		DaemonFileUtils.ln("webapps/"+HttpdTomcatContext.ROOT_DOC_BASE, siteDir+"/htdocs", uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/lib", 0770, uid, gid);
		DaemonFileUtils.ln("var/log", siteDir+"/logs", uid, gid);
		DaemonFileUtils.ln("webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/classes", siteDir+"/servlet", uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/var", 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/var/log", 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/var/run", 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/webapps", 0775, uid, gid);

		String templateDir = jbossSite.getHttpdJBossVersion().getTemplateDirectory();
		File f = new File(templateDir);
		String[] contents = f.list();
		String[] command = new String[contents.length+3];
		command[0] = "/bin/cp";
		command[1] = "-rdp";
		command[command.length-1] = siteDir;
		for (int i = 0; i < contents.length; i++) command[i+2] = templateDir+"/"+contents[i];
		AOServDaemon.exec(command);
		// chown
		AOServDaemon.exec(
			"/bin/chown",
			"-R",
			laUsername,
			siteDir+"/jboss",
			siteDir+"/bin",
			siteDir+"/lib",
			siteDir+"/daemon"
		);
		// chgrp
		AOServDaemon.exec(
			"/bin/chgrp",
			"-R",
			laGroupname,
			siteDir+"/jboss",
			siteDir+"/bin",
			siteDir+"/lib",
			siteDir+"/daemon"
		);

		String jbossConfDir = siteDir+"/jboss/conf/tomcat";
		File f2 = new File(jbossConfDir);
		String[] f2contents = f2.list();

		String[] command3 = new String[5];
		command3[0] = replaceCommand;
		command3[3] = "--";
		for (int i = 0; i < 5; i++) {
			for (int j = 0; j < f2contents.length; j++) {
				switch (i) {
					case 0: command3[1] = "2222"; command3[2] = String.valueOf(jbossSite.getJnpBind().getPort().getPort()); break;
					case 1: command3[1] = "3333"; command3[2] = String.valueOf(jbossSite.getWebserverBind().getPort().getPort()); break;
					case 2: command3[1] = "4444"; command3[2] = String.valueOf(jbossSite.getRmiBind().getPort().getPort()); break;
					case 3: command3[1] = "5555"; command3[2] = String.valueOf(jbossSite.getHypersonicBind().getPort().getPort()); break;
					case 4: command3[1] = "6666"; command3[2] = String.valueOf(jbossSite.getJmxBind().getPort().getPort()); break;
				}
				command3[4] = jbossConfDir+"/"+f2contents[j];
				AOServDaemon.exec(command3);
			}
		}
		AOServDaemon.exec(
			replaceCommand,
			"site_name",
			siteName,
			"--",
			siteDir+"/bin/jboss",
			siteDir+"/bin/profile.jboss",
			siteDir+"/bin/profile.user"
		);
		DaemonFileUtils.ln(".", siteDir+"/tomcat", uid, gid);

		DaemonFileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE, 0775, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/META-INF", 0775, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF", 0775, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/classes", 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/cocoon", 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/conf", 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/lib", 0770, uid, gid);	
		DaemonFileUtils.mkdir(siteDir+"/work", 0750, uid, gid);

		/*
		 * Set up the bash profile source
		 */
		String profileFile=siteDir+"/bin/profile.jboss";
		LinuxAccountManager.setBashProfile(lsa, profileFile);

		/*
		 * The classes directory
		 */
		DaemonFileUtils.mkdir(siteDir+"/classes", 0770, uid, gid);

		AOServer thisAoServer = AOServDaemon.getThisAOServer();
		int uid_min = thisAoServer.getUidMin().getId();
		int gid_min = thisAoServer.getGidMin().getId();
		/*
		 * Write the manifest.servlet file.
		 */
		String confManifestServlet=siteDir+"/conf/manifest.servlet";
		ChainWriter out=new ChainWriter(
			new BufferedOutputStream(
				new UnixFile(confManifestServlet).getSecureOutputStream(uid, gid, 0660, false, uid_min, gid_min)
			)
		);
		try {
			out.print("Manifest-version: 1.0\n"
					  + "Name: javax/servlet\n"
					  + "Sealed: true\n"
					  + "Specification-Title: \"Java Servlet API\"\n"
					  + "Specification-Version: \"2.1.1\"\n"
					  + "Specification-Vendor: \"Sun Microsystems, Inc.\"\n"
					  + "Implementation-Title: \"javax.servlet\"\n"
					  + "Implementation-Version: \"2.1.1\"\n"
					  + "Implementation-Vendor: \"Sun Microsystems, Inc.\"\n"
					  + "\n"
					  + "Name: javax/servlet/http\n"
					  + "Sealed: true\n"
					  + "Specification-Title: \"Java Servlet API\"\n"
					  + "Specification-Version: \"2.1.1\"\n"
					  + "Specification-Vendor: \"Sun Microsystems, Inc.\"\n"
					  + "Implementation-Title: \"javax.servlet\"\n"
					  + "Implementation-Version: \"2.1.1\"\n"
					  + "Implementation-Vendor: \"Sun Microsystems, Inc.\"\n"
					  );
		} finally {
			out.close();
		}

		/*
		 * Create the conf/server.dtd file.
		 */
		String confServerDTD=siteDir+"/conf/server.dtd";
		out=new ChainWriter(
			new BufferedOutputStream(
				new UnixFile(confServerDTD).getSecureOutputStream(uid, gid, 0660, false, uid_min, gid_min)
			)
		);
		try {
			out.print("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n"
					  + "\n"
					  + "<!ELEMENT Server (ContextManager+)>\n"
					  + "<!ATTLIST Server\n"
					  + "    adminPort NMTOKEN \"-1\"\n"
					  + "    workDir CDATA \"work\">\n"
					  + "\n"
					  + "<!ELEMENT ContextManager (Context+, Interceptor*, Connector+)>\n"
					  + "<!ATTLIST ContextManager\n"
					  + "    port NMTOKEN \"8080\"\n"
					  + "    hostName NMTOKEN \"\"\n"
					  + "    inet NMTOKEN \"\">\n"
					  + "\n"
					  + "<!ELEMENT Context EMPTY>\n"
					  + "<!ATTLIST Context\n"
					  + "    path CDATA #REQUIRED\n"
					  + "    docBase CDATA #REQUIRED\n"
					  + "    defaultSessionTimeOut NMTOKEN \"30\"\n"
					  + "    isWARExpanded (true | false) \"true\"\n"
					  + "    isWARValidated (false | true) \"false\"\n"
					  + "    isInvokerEnabled (true | false) \"true\"\n"
					  + "    isWorkDirPersistent (false | true) \"false\">\n"
					  + "\n"
					  + "<!ELEMENT Interceptor EMPTY>\n"
					  + "<!ATTLIST Interceptor\n"
					  + "    className NMTOKEN #REQUIRED\n"
					  + "    docBase   CDATA #REQUIRED>\n"
					  + "\n"
					  + "<!ELEMENT Connector (Parameter*)>\n"
					  + "<!ATTLIST Connector\n"
					  + "    className NMTOKEN #REQUIRED>\n"
					  + "\n"
					  + "<!ELEMENT Parameter EMPTY>\n"
					  + "<!ATTLIST Parameter\n"
					  + "    name CDATA #REQUIRED\n"
					  + "    value CDATA \"\">\n"
					  );
		} finally {
			out.close();
		}

		/*
		 * Create the test-tomcat.xml file.
		 */
		tomcatCommon.createTestTomcatXml(siteDir+"/conf", uid, gid, 0660, uid_min, gid_min);

		/*
		 * Create the tomcat-users.xml file
		 */
		String confTomcatUsers=siteDir+"/conf/tomcat-users.xml";
		out=new ChainWriter(
			new BufferedOutputStream(
				new UnixFile(confTomcatUsers).getSecureOutputStream(uid, gid, 0660, false, uid_min, gid_min)
			)
		);
		try {
			tomcatCommon.printTomcatUsers(out);
		} finally {
			out.close();
		}

		/*
		 * Create the web.dtd file.
		 */
		tomcatCommon.createWebDtd(siteDir+"/conf", uid, gid, 0660, uid_min, gid_min);

		/*
		 * Create the web.xml file.
		 */
		tomcatCommon.createWebXml(siteDir+"/conf", uid, gid, 0660, uid_min, gid_min);

		/*
		 * Create the empty log files.
		 */
		for(int c=0;c<TomcatCommon_3_X.tomcatLogFiles.length;c++) {
			String filename=siteDir+"/var/log/"+TomcatCommon_3_X.tomcatLogFiles[c];
			new UnixFile(filename).getSecureOutputStream(uid, gid, 0660, false, uid_min, gid_min).close();
		}

		/*
		 * Create the manifest file.
		 */
		String manifestFile=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/META-INF/MANIFEST.MF";
		new ChainWriter(
			new UnixFile(manifestFile).getSecureOutputStream(
				uid,
				gid,
				0664,
				false,
				uid_min,
				gid_min
			)
		).print("Manifest-Version: 1.0").close();

		/*
		 * Write the cocoon.properties file.
		 */
		String cocoonProps=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/conf/cocoon.properties";
		OutputStream fileOut=new BufferedOutputStream(new UnixFile(cocoonProps).getSecureOutputStream(uid, gid, 0660, false, uid_min, gid_min));
		try {
			tomcatCommon.copyCocoonProperties1(fileOut);
			out=new ChainWriter(fileOut);
			try {
				out.print("processor.xsp.repository = ").print(siteDir).print("/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/cocoon\n");
				out.flush();
				tomcatCommon.copyCocoonProperties2(fileOut);
			} finally {
				out.flush();
			}
		} finally {
			fileOut.close();
		}

		/*
		 * Write the ROOT/WEB-INF/web.xml file.
		 */
		String webXML=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/web.xml";
		out=new ChainWriter(
			new BufferedOutputStream(
				new UnixFile(webXML).getSecureOutputStream(uid, gid, 0664, false, uid_min, gid_min)
			)
		);
		try {
			out.print("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n"
					  + "\n"
					  + "<!DOCTYPE web-app\n"
					  + "    PUBLIC \"-//Sun Microsystems, Inc.//DTD Web Application 2.2//EN\"\n"
					  + "    \"http://java.sun.com/j2ee/dtds/web-app_2.2.dtd\">\n"
					  + "\n"
					  + "<web-app>\n"
					  + "\n"
					  + " <servlet>\n"
					  + "  <servlet-name>org.apache.cocoon.Cocoon</servlet-name>\n"
					  + "  <servlet-class>org.apache.cocoon.Cocoon</servlet-class>\n"
					  + "  <init-param>\n"
					  + "   <param-name>properties</param-name>\n"
					  + "   <param-value>\n"
					  + "    WEB-INF/conf/cocoon.properties\n"
					  + "   </param-value>\n"
					  + "  </init-param>\n"
					  + " </servlet>\n"
					  + "\n"
					  + " <servlet-mapping>\n"
					  + "  <servlet-name>org.apache.cocoon.Cocoon</servlet-name>\n"
					  + "  <url-pattern>*.xml</url-pattern>\n"
					  + " </servlet-mapping>\n"
					  + "\n"
					  + "</web-app>\n");
		} finally {
			out.close();
		}
	}

	@Override
	public TomcatCommon_3_2_4 getTomcatCommon() {
		return TomcatCommon_3_2_4.getInstance();
	}

	/**
	 * Only supports ajp12
	 */
	@Override
	protected HttpdWorker getHttpdWorker() throws IOException, SQLException {
		AOServConnector conn = AOServDaemon.getConnector();
		List<HttpdWorker> workers = tomcatSite.getHttpdWorkers();

		// Try ajp12 next
		for(HttpdWorker hw : workers) {
			if(hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol().equals(HttpdJKProtocol.AJP12)) return hw;
		}
		throw new SQLException("Couldn't find ajp12");
	}

	@Override
	protected void enableDisable(UnixFile siteDirectory) throws IOException, SQLException {
		UnixFile daemonUF = new UnixFile(siteDirectory, "daemon", false);
		UnixFile daemonSymlink = new UnixFile(daemonUF, "jboss", false);
		if(!httpdSite.isDisabled()) {
			// Enabled
			if(!daemonSymlink.getStat().exists()) {
				daemonSymlink.symLink("../bin/jboss").chown(
					httpdSite.getLinuxServerAccount().getUid().getId(),
					httpdSite.getLinuxServerGroup().getGid().getId()
				);
			}
		} else {
			// Disabled
			if(daemonSymlink.getStat().exists()) daemonSymlink.delete();
		}
	}

	/**
	 * Builds the server.xml file.
	 */
	protected byte[] buildServerXml(UnixFile siteDirectory, String autoWarning) throws IOException, SQLException {
		final String siteDir = siteDirectory.getPath();
		final AOServConnector conn = AOServDaemon.getConnector();
		final HttpdTomcatVersion htv = tomcatSite.getHttpdTomcatVersion();

		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		try (ChainWriter out = new ChainWriter(bout)) {
			out.print("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n");
			if(!httpdSite.isManual()) out.print(autoWarning);
			out.print("<Server>\n"
					+ "  <xmlmapper:debug level=\"0\" />\n"
					+ "  <Logger name=\"tc_log\" verbosityLevel = \"INFORMATION\" path=\"").print(siteDir).print("/var/log/tomcat.log\" />\n"
					+ "  <Logger name=\"servlet_log\" path=\"").print(siteDir).print("/var/log/servlet.log\" />\n"
					+ "  <Logger name=\"JASPER_LOG\" path=\"").print(siteDir).print("/var/log/jasper.log\" verbosityLevel = \"INFORMATION\" />\n"
					+ "\n"
					+ "  <ContextManager debug=\"0\" home=\"").print(siteDir).print("\" workDir=\"").print(siteDir).print("/work").print("\" showDebugInfo=\"true\" >\n"
					+ "    <ContextInterceptor className=\"org.apache.tomcat.context.WebXmlReader\" />\n"
					+ "    <ContextInterceptor className=\"org.apache.tomcat.context.LoaderInterceptor\" />\n"
					+ "    <ContextInterceptor className=\"org.apache.tomcat.context.DefaultCMSetter\" />\n"
					+ "    <ContextInterceptor className=\"org.apache.tomcat.context.WorkDirInterceptor\" />\n"
					+ "    <RequestInterceptor className=\"org.apache.tomcat.request.Jdk12Interceptor\" />\n"
					+ "    <RequestInterceptor className=\"org.apache.tomcat.request.SessionInterceptor\" noCookies=\"false\" />\n"
					+ "    <RequestInterceptor className=\"org.apache.tomcat.request.SimpleMapper1\" debug=\"0\" />\n"
					+ "    <RequestInterceptor className=\"org.apache.tomcat.request.InvokerInterceptor\" debug=\"0\" prefix=\"/servlet/\" />\n"
					+ "    <RequestInterceptor className=\"org.apache.tomcat.request.StaticInterceptor\" debug=\"0\" suppress=\"false\" />\n"
					+ "    <RequestInterceptor className=\"org.apache.tomcat.session.StandardSessionInterceptor\" />\n"
					+ "    <RequestInterceptor className=\"org.apache.tomcat.request.AccessInterceptor\" debug=\"0\" />\n"
					+ "    <RequestInterceptor className=\"org.jboss.tomcat.security.JBossSecurityMgrRealm\" />\n"
					+ "    <ContextInterceptor className=\"org.apache.tomcat.context.LoadOnStartupInterceptor\" />\n");

			for(HttpdWorker worker : tomcatSite.getHttpdWorkers()) {
				NetBind netBind=worker.getNetBind();
				String protocol=worker.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol();

				out.print("    <Connector className=\"org.apache.tomcat.service.PoolTcpConnector\">\n"
						+ "      <Parameter name=\"handler\" value=\"");
				switch (protocol) {
					case HttpdJKProtocol.AJP12:
						out.print("org.apache.tomcat.service.connector.Ajp12ConnectionHandler");
						break;
					case HttpdJKProtocol.AJP13:
						out.print("org.apache.tomcat.service.connector.Ajp13ConnectionHandler");
						break;
					default:
						throw new IllegalArgumentException("Unknown AJP version: "+htv);
				}
				out.print("\"/>\n"
						+ "      <Parameter name=\"port\" value=\"").print(netBind.getPort().getPort()).print("\"/>\n");
				InetAddress ip=netBind.getIPAddress().getInetAddress();
				if(!ip.isUnspecified()) out.print("      <Parameter name=\"inet\" value=\"").encodeXmlAttribute(ip.toString()).print("\"/>\n");
				out.print("      <Parameter name=\"max_threads\" value=\"30\"/>\n"
						+ "      <Parameter name=\"max_spare_threads\" value=\"10\"/>\n"
						+ "      <Parameter name=\"min_spare_threads\" value=\"1\"/>\n"
						+ "    </Connector>\n"
				);
			}
			for(HttpdTomcatContext htc : tomcatSite.getHttpdTomcatContexts()) {
				out.print("    <Context path=\"").print(htc.getPath()).print("\" docBase=\"").print(htc.getDocBase()).print("\" debug=\"").print(htc.getDebugLevel()).print("\" reloadable=\"").print(htc.isReloadable()).print("\" />\n");
			}
			out.print("  </ContextManager>\n"
					+ "</Server>\n");
		}
		return bout.toByteArray();
	}

	@Override
	protected boolean rebuildConfigFiles(UnixFile siteDirectory) throws IOException, SQLException {
		final String siteDir = siteDirectory.getPath();
		boolean needsRestart = false;
		String autoWarning = getAutoWarningXml();
		String autoWarningOld = getAutoWarningXmlOld();

		AOServer thisAoServer = AOServDaemon.getThisAOServer();
		int uid_min = thisAoServer.getUidMin().getId();
		int gid_min = thisAoServer.getGidMin().getId();

		String confServerXML=siteDir+"/conf/server.xml";
		UnixFile confServerXMLFile=new UnixFile(confServerXML);
		if(!httpdSite.isManual() || !confServerXMLFile.getStat().exists()) {
			// Only write to the actual file when missing or changed
			if(
				DaemonFileUtils.writeIfNeeded(
					buildServerXml(siteDirectory, autoWarning),
					null,
					confServerXMLFile,
					httpdSite.getLinuxServerAccount().getUid().getId(),
					httpdSite.getLinuxServerGroup().getGid().getId(),
					0660,
					uid_min,
					gid_min
				)
			) {
				// Flag as needing restarted
				needsRestart = true;
			}
		} else {
			try {
				DaemonFileUtils.stripFilePrefix(
					confServerXMLFile,
					autoWarningOld,
					uid_min,
					gid_min
				);
				DaemonFileUtils.stripFilePrefix(
					confServerXMLFile,
					autoWarning,
					uid_min,
					gid_min
				);
			} catch(IOException err) {
				// Errors OK because this is done in manual mode and they might have symbolic linked stuff
			}
		}
		return needsRestart;
	}

	@Override
	protected boolean upgradeSiteDirectoryContents(UnixFile siteDirectory) throws IOException, SQLException {
		// TODO
		return false;
	}
}
