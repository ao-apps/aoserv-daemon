/*
 * Copyright 2008-2013 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.HttpdTomcatDataSource;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.UpgradeSymlink;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import java.io.IOException;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Set;

/**
 * Some common code for Tomcat 5.5.X
 *
 * @author  AO Industries, Inc.
 */
class TomcatCommon_5_5_X extends TomcatCommon {

	private static final TomcatCommon_5_5_X instance = new TomcatCommon_5_5_X();
	static TomcatCommon_5_5_X getInstance() {
		return instance;
	}

	private TomcatCommon_5_5_X() {}

	@Override
	protected Set<PackageManager.PackageName> getRequiredPackages() {
		return EnumSet.of(PackageManager.PackageName.APACHE_TOMCAT_5_5);
	}

	@Override
	public void writeHttpdTomcatDataSource(HttpdTomcatDataSource dataSource, ChainWriter out) throws SQLException, IOException {
		out.print("          <Resource\n"
				+ "            name=\"").encodeXmlAttribute(dataSource.getName()).print("\"\n"
				+ "            auth=\"Container\"\n"
				+ "            type=\"javax.sql.DataSource\"\n"
				+ "            username=\"").encodeXmlAttribute(dataSource.getUsername()).print("\"\n"
				+ "            password=\"").encodeXmlAttribute(dataSource.getPassword()).print("\"\n"
				+ "            driverClassName=\"").encodeXmlAttribute(dataSource.getDriverClassName()).print("\"\n"
				+ "            url=\"").encodeXmlAttribute(dataSource.getUrl()).print("\"\n"
				+ "            maxActive=\"").print(dataSource.getMaxActive()).print("\"\n"
				+ "            maxIdle=\"").print(dataSource.getMaxIdle()).print("\"\n"
				+ "            maxWait=\"").print(dataSource.getMaxWait()).print("\"\n");
		if(dataSource.getValidationQuery()!=null) {
			out.print("            validationQuery=\"").encodeXmlAttribute(dataSource.getValidationQuery()).print("\"\n");
		}
		out.print("            removeAbandoned=\"true\"\n"
				+ "            removeAbandonedTimeout=\"300\"\n"
				+ "            logAbandoned=\"true\"\n"
				+ "          />\n");
	}

	private static final UpgradeSymlink[] upgradeSymlinks_5_5 = {
		// Upgrade from Tomcat 5.5.20
		new UpgradeSymlink(
			"bin/bootstrap.jar",
			"../../../opt/apache-tomcat-5.5.20/bin/bootstrap.jar",
			"../../../opt/apache-tomcat-5.5/bin/bootstrap.jar"
		),
		new UpgradeSymlink(
			"bin/catalina.sh",
			"../../../opt/apache-tomcat-5.5.20/bin/catalina.sh",
			"../../../opt/apache-tomcat-5.5/bin/catalina.sh"
		),
		new UpgradeSymlink(
			"bin/commons-daemon.jar",
			"../../../opt/apache-tomcat-5.5.20/bin/commons-daemon.jar",
			"../../../opt/apache-tomcat-5.5/bin/commons-daemon.jar"
		),
		new UpgradeSymlink(
			"bin/commons-logging-api.jar",
			"../../../opt/apache-tomcat-5.5.20/bin/commons-logging-api.jar",
			null
		),
		new UpgradeSymlink(
			"bin/digest.sh",
			"../../../opt/apache-tomcat-5.5.20/bin/digest.sh",
			"../../../opt/apache-tomcat-5.5/bin/digest.sh"
		),
		new UpgradeSymlink(
			"bin/setclasspath.sh",
			"../../../opt/apache-tomcat-5.5.20/bin/setclasspath.sh",
			"../../../opt/apache-tomcat-5.5/bin/setclasspath.sh"
		),
		new UpgradeSymlink(
			"bin/tomcat-juli.jar",
			"../../../opt/apache-tomcat-5.5.20/bin/tomcat-juli.jar",
			"../../../opt/apache-tomcat-5.5/bin/tomcat-juli.jar"
		),
		new UpgradeSymlink(
			"bin/tool-wrapper.sh",
			"../../../opt/apache-tomcat-5.5.20/bin/tool-wrapper.sh",
			"../../../opt/apache-tomcat-5.5/bin/tool-wrapper.sh"
		),
		new UpgradeSymlink(
			"bin/version.sh",
			"../../../opt/apache-tomcat-5.5.20/bin/version.sh",
			"../../../opt/apache-tomcat-5.5/bin/version.sh"
		),
		new UpgradeSymlink(
			"common/i18n/tomcat-i18n-en.jar",
			"../../../../opt/apache-tomcat-5.5.20/common/i18n/tomcat-i18n-en.jar",
			"../../../../opt/apache-tomcat-5.5/common/i18n/tomcat-i18n-en.jar"
		),
		new UpgradeSymlink(
			"common/i18n/tomcat-i18n-es.jar",
			"../../../../opt/apache-tomcat-5.5.20/common/i18n/tomcat-i18n-es.jar",
			"../../../../opt/apache-tomcat-5.5/common/i18n/tomcat-i18n-es.jar"
		),
		new UpgradeSymlink(
			"common/i18n/tomcat-i18n-fr.jar",
			"../../../../opt/apache-tomcat-5.5.20/common/i18n/tomcat-i18n-fr.jar",
			"../../../../opt/apache-tomcat-5.5/common/i18n/tomcat-i18n-fr.jar"
		),
		new UpgradeSymlink(
			"common/i18n/tomcat-i18n-ja.jar",
			"../../../../opt/apache-tomcat-5.5.20/common/i18n/tomcat-i18n-ja.jar",
			"../../../../opt/apache-tomcat-5.5/common/i18n/tomcat-i18n-ja.jar"
		),
		new UpgradeSymlink(
			"common/lib/commons-el.jar",
			"../../../../opt/apache-tomcat-5.5.20/common/lib/commons-el.jar",
			"../../../../opt/apache-tomcat-5.5/common/lib/commons-el.jar"
		),
		new UpgradeSymlink(
			"common/lib/jasper-compiler.jar",
			"../../../../opt/apache-tomcat-5.5.20/common/lib/jasper-compiler.jar",
			"../../../../opt/apache-tomcat-5.5/common/lib/jasper-compiler.jar"
		),
		new UpgradeSymlink(
			"common/lib/jasper-compiler-jdt.jar",
			"../../../../opt/apache-tomcat-5.5.20/common/lib/jasper-compiler-jdt.jar",
			null
		),
		new UpgradeSymlink(
			"common/lib/jasper-runtime.jar",
			"../../../../opt/apache-tomcat-5.5.20/common/lib/jasper-runtime.jar",
			"../../../../opt/apache-tomcat-5.5/common/lib/jasper-runtime.jar"
		),
		new UpgradeSymlink(
			"common/lib/jsp-api.jar",
			"../../../../opt/apache-tomcat-5.5.20/common/lib/jsp-api.jar",
			"../../../../opt/apache-tomcat-5.5/common/lib/jsp-api.jar"
		),
		new UpgradeSymlink(
			"common/lib/naming-factory-dbcp.jar",
			"../../../../opt/apache-tomcat-5.5.20/common/lib/naming-factory-dbcp.jar",
			"../../../../opt/apache-tomcat-5.5/common/lib/naming-factory-dbcp.jar"
		),
		new UpgradeSymlink(
			"common/lib/naming-factory.jar",
			"../../../../opt/apache-tomcat-5.5.20/common/lib/naming-factory.jar",
			"../../../../opt/apache-tomcat-5.5/common/lib/naming-factory.jar"
		),
		new UpgradeSymlink(
			"common/lib/naming-resources.jar",
			"../../../../opt/apache-tomcat-5.5.20/common/lib/naming-resources.jar",
			"../../../../opt/apache-tomcat-5.5/common/lib/naming-resources.jar"
		),
		new UpgradeSymlink(
			"common/lib/servlet-api.jar",
			"../../../../opt/apache-tomcat-5.5.20/common/lib/servlet-api.jar",
			"../../../../opt/apache-tomcat-5.5/common/lib/servlet-api.jar"
		),
		new UpgradeSymlink(
			"server/lib/catalina-ant.jar",
			"../../../../opt/apache-tomcat-5.5.20/server/lib/catalina-ant.jar",
			"../../../../opt/apache-tomcat-5.5/server/lib/catalina-ant.jar"
		),
		new UpgradeSymlink(
			"server/lib/catalina-ant-jmx.jar",
			"../../../../opt/apache-tomcat-5.5.20/server/lib/catalina-ant-jmx.jar",
			"../../../../opt/apache-tomcat-5.5/server/lib/catalina-ant-jmx.jar"
		),
		new UpgradeSymlink(
			"server/lib/catalina-cluster.jar",
			"../../../../opt/apache-tomcat-5.5.20/server/lib/catalina-cluster.jar",
			"../../../../opt/apache-tomcat-5.5/server/lib/catalina-cluster.jar"
		),
		new UpgradeSymlink(
			"server/lib/catalina.jar",
			"../../../../opt/apache-tomcat-5.5.20/server/lib/catalina.jar",
			"../../../../opt/apache-tomcat-5.5/server/lib/catalina.jar"
		),
		new UpgradeSymlink(
			"server/lib/catalina-optional.jar",
			"../../../../opt/apache-tomcat-5.5.20/server/lib/catalina-optional.jar",
			"../../../../opt/apache-tomcat-5.5/server/lib/catalina-optional.jar"
		),
		new UpgradeSymlink(
			"server/lib/catalina-storeconfig.jar",
			"../../../../opt/apache-tomcat-5.5.20/server/lib/catalina-storeconfig.jar",
			"../../../../opt/apache-tomcat-5.5/server/lib/catalina-storeconfig.jar"
		),
		new UpgradeSymlink(
			"server/lib/commons-modeler.jar",
			"../../../../opt/apache-tomcat-5.5.20/server/lib/commons-modeler.jar",
			null
		),
		new UpgradeSymlink(
			"server/lib/servlets-cgi.renametojar",
			"../../../../opt/apache-tomcat-5.5.20/server/lib/servlets-cgi.renametojar",
			"../../../../opt/apache-tomcat-5.5/server/lib/servlets-cgi.renametojar"
		),
		new UpgradeSymlink(
			"server/lib/servlets-default.jar",
			"../../../../opt/apache-tomcat-5.5.20/server/lib/servlets-default.jar",
			"../../../../opt/apache-tomcat-5.5/server/lib/servlets-default.jar"
		),
		new UpgradeSymlink(
			"server/lib/servlets-invoker.jar",
			"../../../../opt/apache-tomcat-5.5.20/server/lib/servlets-invoker.jar",
			"../../../../opt/apache-tomcat-5.5/server/lib/servlets-invoker.jar"
		),
		new UpgradeSymlink(
			"server/lib/servlets-ssi.renametojar",
			"../../../../opt/apache-tomcat-5.5.20/server/lib/servlets-ssi.renametojar",
			"../../../../opt/apache-tomcat-5.5/server/lib/servlets-ssi.renametojar"
		),
		new UpgradeSymlink(
			"server/lib/servlets-webdav.jar",
			"../../../../opt/apache-tomcat-5.5.20/server/lib/servlets-webdav.jar",
			"../../../../opt/apache-tomcat-5.5/server/lib/servlets-webdav.jar"
		),
		new UpgradeSymlink(
			"server/lib/tomcat-ajp.jar",
			"../../../../opt/apache-tomcat-5.5.20/server/lib/tomcat-ajp.jar",
			"../../../../opt/apache-tomcat-5.5/server/lib/tomcat-ajp.jar"
		),
		new UpgradeSymlink(
			"server/lib/tomcat-apr.jar",
			"../../../../opt/apache-tomcat-5.5.20/server/lib/tomcat-apr.jar",
			"../../../../opt/apache-tomcat-5.5/server/lib/tomcat-apr.jar"
		),
		new UpgradeSymlink(
			"server/lib/tomcat-coyote.jar",
			"../../../../opt/apache-tomcat-5.5.20/server/lib/tomcat-coyote.jar",
			"../../../../opt/apache-tomcat-5.5/server/lib/tomcat-coyote.jar"
		),
		new UpgradeSymlink(
			"server/lib/tomcat-http.jar",
			"../../../../opt/apache-tomcat-5.5.20/server/lib/tomcat-http.jar",
			"../../../../opt/apache-tomcat-5.5/server/lib/tomcat-http.jar"
		),
		new UpgradeSymlink(
			"server/lib/tomcat-jkstatus-ant.jar",
			"../../../../opt/apache-tomcat-5.5.20/server/lib/tomcat-jkstatus-ant.jar",
			null
		),
		new UpgradeSymlink(
			"server/lib/tomcat-util.jar",
			"../../../../opt/apache-tomcat-5.5.20/server/lib/tomcat-util.jar",
			"../../../../opt/apache-tomcat-5.5/server/lib/tomcat-util.jar"
		),
		// Links to remove entirely
		new UpgradeSymlink(
			"bin/commons-logging-api.jar",
			"../../../opt/apache-tomcat-5.5/bin/commons-logging-api.jar",
			null
		),
		new UpgradeSymlink(
			"common/lib/jasper-compiler-jdt.jar",
			"../../../../opt/apache-tomcat-5.5/common/lib/jasper-compiler-jdt.jar",
			null
		),
		new UpgradeSymlink(
			"server/lib/commons-modeler.jar",
			"../../../../opt/apache-tomcat-5.5/server/lib/commons-modeler.jar",
			null
		),
		new UpgradeSymlink(
			"server/lib/tomcat-jkstatus-ant.jar",
			"../../../../opt/apache-tomcat-5.5/server/lib/tomcat-jkstatus-ant.jar",
			null
		),
		// New links for current Tomcat version
		new UpgradeSymlink(
			"bin/commons-logging-api-1.1.1.jar",
			null,
			"../../../opt/apache-tomcat-5.5/bin/commons-logging-api-1.1.1.jar"
		),
		new UpgradeSymlink(
			"common/lib/ecj-3.7.2.jar",
			null,
			"../../../../opt/apache-tomcat-5.5/common/lib/ecj-3.7.2.jar"
		),
		new UpgradeSymlink(
			"server/lib/commons-modeler-2.0.1.jar",
			null,
			"../../../../opt/apache-tomcat-5.5/server/lib/commons-modeler-2.0.1.jar"
		)
	};

	/**
	 * Upgrades the Tomcat 5.5.X installed in the provided directory.
	 */
	boolean upgradeTomcatDirectory(UnixFile tomcatDirectory, int uid, int gid) throws IOException, SQLException {
		boolean needsRestart = false;
		OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
		if(osConfig==OperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64) {
			// Tomcat 5.5.Latest
			for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_5_5) {
				if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
			}
			// MySQL
			//for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_MySQL) {
			//	if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
			//}
			// PostgreSQL
			//for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_PostgreSQL) {
			//	if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
			//}
			// Replace /usr/aoserv/etc in bin/profile
			/*
			String results = AOServDaemon.execAndCapture(
				new String[] {
					osConfig.getReplaceCommand(),
					"/aoserv.sh",
					"/aoserv-client.sh",
					"--",
					tomcatDirectory.getPath()+"/bin/profile"
				}
			);
			if(results.length()>0) needsRestart = true;
			 */
		}
		return needsRestart;
	}
}
