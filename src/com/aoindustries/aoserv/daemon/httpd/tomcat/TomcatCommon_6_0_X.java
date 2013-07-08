/*
 * Copyright 2008-2013 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.HttpdTomcatDataSource;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.util.UpgradeSymlink;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Some common code for Tomcat 6.0.X
 *
 * @author  AO Industries, Inc.
 */
class TomcatCommon_6_0_X extends TomcatCommon {

    private static final TomcatCommon_6_0_X instance = new TomcatCommon_6_0_X();
    static TomcatCommon_6_0_X getInstance() {
        return instance;
    }

    private TomcatCommon_6_0_X() {}

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

    /**
     * Uses os-default JDK.
     *
     * @see  OperatingSystemConfiguration#getDefaultJdkVersion
     */
    public String getDefaultJdkVersion() throws IOException, SQLException {
        return OperatingSystemConfiguration.getOperatingSystemConfiguration().getDefaultJdkVersion();
    }

	private static final UpgradeSymlink[] upgradeSymlinks_6_0 = {
		// Upgrade from Tomcat 6.0.14
		new UpgradeSymlink(
			"bin/bootstrap.jar",
			"../../../opt/apache-tomcat-6.0.14/bin/bootstrap.jar",
			"../../../opt/apache-tomcat-6.0/bin/bootstrap.jar"
		),
		new UpgradeSymlink(
			"bin/catalina.sh",
			"../../../opt/apache-tomcat-6.0.14/bin/catalina.sh",
			"../../../opt/apache-tomcat-6.0/bin/catalina.sh"
		),
		new UpgradeSymlink(
			"bin/commons-daemon.jar",
			"../../../opt/apache-tomcat-6.0.14/bin/commons-daemon.jar",
			"../../../opt/apache-tomcat-6.0/bin/commons-daemon.jar"
		),
		new UpgradeSymlink(
			"bin/digest.sh",
			"../../../opt/apache-tomcat-6.0.14/bin/digest.sh",
			"../../../opt/apache-tomcat-6.0/bin/digest.sh"
		),
		new UpgradeSymlink(
			"bin/setclasspath.sh",
			"../../../opt/apache-tomcat-6.0.14/bin/setclasspath.sh",
			"../../../opt/apache-tomcat-6.0/bin/setclasspath.sh"
		),
		new UpgradeSymlink(
			"bin/tomcat-juli.jar",
			"../../../opt/apache-tomcat-6.0.14/bin/tomcat-juli.jar",
			"../../../opt/apache-tomcat-6.0/bin/tomcat-juli.jar"
		),
		new UpgradeSymlink(
			"bin/tool-wrapper.sh",
			"../../../opt/apache-tomcat-6.0.14/bin/tool-wrapper.sh",
			"../../../opt/apache-tomcat-6.0/bin/tool-wrapper.sh"
		),
		new UpgradeSymlink(
			"bin/version.sh",
			"../../../opt/apache-tomcat-6.0.14/bin/version.sh",
			"../../../opt/apache-tomcat-6.0/bin/version.sh"
		),
		new UpgradeSymlink(
			"lib/annotations-api.jar",
			"../../../opt/apache-tomcat-6.0.14/lib/annotations-api.jar",
			"../../../opt/apache-tomcat-6.0/lib/annotations-api.jar"
		),
		new UpgradeSymlink(
			"lib/catalina-ant.jar",
			"../../../opt/apache-tomcat-6.0.14/lib/catalina-ant.jar",
			"../../../opt/apache-tomcat-6.0/lib/catalina-ant.jar"
		),
		new UpgradeSymlink(
			"lib/catalina-ha.jar",
			"../../../opt/apache-tomcat-6.0.14/lib/catalina-ha.jar",
			"../../../opt/apache-tomcat-6.0/lib/catalina-ha.jar"
		),
		new UpgradeSymlink(
			"lib/catalina.jar",
			"../../../opt/apache-tomcat-6.0.14/lib/catalina.jar",
			"../../../opt/apache-tomcat-6.0/lib/catalina.jar"
		),
		new UpgradeSymlink(
			"lib/catalina-tribes.jar",
			"../../../opt/apache-tomcat-6.0.14/lib/catalina-tribes.jar",
			"../../../opt/apache-tomcat-6.0/lib/catalina-tribes.jar"
		),
		new UpgradeSymlink(
			"lib/el-api.jar",
			"../../../opt/apache-tomcat-6.0.14/lib/el-api.jar",
			"../../../opt/apache-tomcat-6.0/lib/el-api.jar"
		),
		new UpgradeSymlink(
			"lib/jasper-el.jar",
			"../../../opt/apache-tomcat-6.0.14/lib/jasper-el.jar",
			"../../../opt/apache-tomcat-6.0/lib/jasper-el.jar"
		),
		new UpgradeSymlink(
			"lib/jasper.jar",
			"../../../opt/apache-tomcat-6.0.14/lib/jasper.jar",
			"../../../opt/apache-tomcat-6.0/lib/jasper.jar"
		),
		new UpgradeSymlink(
			"lib/jasper-jdt.jar",
			"../../../opt/apache-tomcat-6.0.14/lib/jasper-jdt.jar",
			null
		),
		new UpgradeSymlink(
			"lib/jsp-api.jar",
			"../../../opt/apache-tomcat-6.0.14/lib/jsp-api.jar",
			"../../../opt/apache-tomcat-6.0/lib/jsp-api.jar"
		),
		new UpgradeSymlink(
			"lib/servlet-api.jar",
			"../../../opt/apache-tomcat-6.0.14/lib/servlet-api.jar",
			"../../../opt/apache-tomcat-6.0/lib/servlet-api.jar"
		),
		new UpgradeSymlink(
			"lib/tomcat-coyote.jar",
			"../../../opt/apache-tomcat-6.0.14/lib/tomcat-coyote.jar",
			"../../../opt/apache-tomcat-6.0/lib/tomcat-coyote.jar"
		),
		new UpgradeSymlink(
			"lib/tomcat-dbcp.jar",
			"../../../opt/apache-tomcat-6.0.14/lib/tomcat-dbcp.jar",
			"../../../opt/apache-tomcat-6.0/lib/tomcat-dbcp.jar"
		),
		new UpgradeSymlink(
			"lib/tomcat-i18n-es.jar",
			"../../../opt/apache-tomcat-6.0.14/lib/tomcat-i18n-es.jar",
			"../../../opt/apache-tomcat-6.0/lib/tomcat-i18n-es.jar"
		),
		new UpgradeSymlink(
			"lib/tomcat-i18n-fr.jar",
			"../../../opt/apache-tomcat-6.0.14/lib/tomcat-i18n-fr.jar",
			"../../../opt/apache-tomcat-6.0/lib/tomcat-i18n-fr.jar"
		),
		new UpgradeSymlink(
			"lib/tomcat-i18n-ja.jar",
			"../../../opt/apache-tomcat-6.0.14/lib/tomcat-i18n-ja.jar",
			"../../../opt/apache-tomcat-6.0/lib/tomcat-i18n-ja.jar"
		),
		// Upgrade from Tomcat 6.0.18
		new UpgradeSymlink(
			"bin/bootstrap.jar",
			"../../../opt/apache-tomcat-6.0.18/bin/bootstrap.jar",
			"../../../opt/apache-tomcat-6.0/bin/bootstrap.jar"
		),
		new UpgradeSymlink(
			"bin/catalina.sh",
			"../../../opt/apache-tomcat-6.0.18/bin/catalina.sh",
			"../../../opt/apache-tomcat-6.0/bin/catalina.sh"
		),
		new UpgradeSymlink(
			"bin/commons-daemon.jar",
			"../../../opt/apache-tomcat-6.0.18/bin/commons-daemon.jar",
			"../../../opt/apache-tomcat-6.0/bin/commons-daemon.jar"
		),
		new UpgradeSymlink(
			"bin/digest.sh",
			"../../../opt/apache-tomcat-6.0.18/bin/digest.sh",
			"../../../opt/apache-tomcat-6.0/bin/digest.sh"
		),
		new UpgradeSymlink(
			"bin/setclasspath.sh",
			"../../../opt/apache-tomcat-6.0.18/bin/setclasspath.sh",
			"../../../opt/apache-tomcat-6.0/bin/setclasspath.sh"
		),
		new UpgradeSymlink(
			"bin/tomcat-juli.jar",
			"../../../opt/apache-tomcat-6.0.18/bin/tomcat-juli.jar",
			"../../../opt/apache-tomcat-6.0/bin/tomcat-juli.jar"
		),
		new UpgradeSymlink(
			"bin/tool-wrapper.sh",
			"../../../opt/apache-tomcat-6.0.18/bin/tool-wrapper.sh",
			"../../../opt/apache-tomcat-6.0/bin/tool-wrapper.sh"
		),
		new UpgradeSymlink(
			"bin/version.sh",
			"../../../opt/apache-tomcat-6.0.18/bin/version.sh",
			"../../../opt/apache-tomcat-6.0/bin/version.sh"
		),
		new UpgradeSymlink(
			"lib/annotations-api.jar",
			"../../../opt/apache-tomcat-6.0.18/lib/annotations-api.jar",
			"../../../opt/apache-tomcat-6.0/lib/annotations-api.jar"
		),
		new UpgradeSymlink(
			"lib/catalina-ant.jar",
			"../../../opt/apache-tomcat-6.0.18/lib/catalina-ant.jar",
			"../../../opt/apache-tomcat-6.0/lib/catalina-ant.jar"
		),
		new UpgradeSymlink(
			"lib/catalina-ha.jar",
			"../../../opt/apache-tomcat-6.0.18/lib/catalina-ha.jar",
			"../../../opt/apache-tomcat-6.0/lib/catalina-ha.jar"
		),
		new UpgradeSymlink(
			"lib/catalina.jar",
			"../../../opt/apache-tomcat-6.0.18/lib/catalina.jar",
			"../../../opt/apache-tomcat-6.0/lib/catalina.jar"
		),
		new UpgradeSymlink(
			"lib/catalina-tribes.jar",
			"../../../opt/apache-tomcat-6.0.18/lib/catalina-tribes.jar",
			"../../../opt/apache-tomcat-6.0/lib/catalina-tribes.jar"
		),
		new UpgradeSymlink(
			"lib/el-api.jar",
			"../../../opt/apache-tomcat-6.0.18/lib/el-api.jar",
			"../../../opt/apache-tomcat-6.0/lib/el-api.jar"
		),
		new UpgradeSymlink(
			"lib/jasper-el.jar",
			"../../../opt/apache-tomcat-6.0.18/lib/jasper-el.jar",
			"../../../opt/apache-tomcat-6.0/lib/jasper-el.jar"
		),
		new UpgradeSymlink(
			"lib/jasper.jar",
			"../../../opt/apache-tomcat-6.0.18/lib/jasper.jar",
			"../../../opt/apache-tomcat-6.0/lib/jasper.jar"
		),
		new UpgradeSymlink(
			"lib/jasper-jdt.jar",
			"../../../opt/apache-tomcat-6.0.18/lib/jasper-jdt.jar",
			null
		),
		new UpgradeSymlink(
			"lib/jsp-api.jar",
			"../../../opt/apache-tomcat-6.0.18/lib/jsp-api.jar",
			"../../../opt/apache-tomcat-6.0/lib/jsp-api.jar"
		),
		new UpgradeSymlink(
			"lib/servlet-api.jar",
			"../../../opt/apache-tomcat-6.0.18/lib/servlet-api.jar",
			"../../../opt/apache-tomcat-6.0/lib/servlet-api.jar"
		),
		new UpgradeSymlink(
			"lib/tomcat-coyote.jar",
			"../../../opt/apache-tomcat-6.0.18/lib/tomcat-coyote.jar",
			"../../../opt/apache-tomcat-6.0/lib/tomcat-coyote.jar"
		),
		new UpgradeSymlink(
			"lib/tomcat-dbcp.jar",
			"../../../opt/apache-tomcat-6.0.18/lib/tomcat-dbcp.jar",
			"../../../opt/apache-tomcat-6.0/lib/tomcat-dbcp.jar"
		),
		new UpgradeSymlink(
			"lib/tomcat-i18n-es.jar",
			"../../../opt/apache-tomcat-6.0.18/lib/tomcat-i18n-es.jar",
			"../../../opt/apache-tomcat-6.0/lib/tomcat-i18n-es.jar"
		),
		new UpgradeSymlink(
			"lib/tomcat-i18n-fr.jar",
			"../../../opt/apache-tomcat-6.0.18/lib/tomcat-i18n-fr.jar",
			"../../../opt/apache-tomcat-6.0/lib/tomcat-i18n-fr.jar"
		),
		new UpgradeSymlink(
			"lib/tomcat-i18n-ja.jar",
			"../../../opt/apache-tomcat-6.0.18/lib/tomcat-i18n-ja.jar",
			"../../../opt/apache-tomcat-6.0/lib/tomcat-i18n-ja.jar"
		),
		// Links to remove entirely
		new UpgradeSymlink(
			"lib/ecj-3.3.1.jar",
			"../../../opt/apache-tomcat-6.0/lib/ecj-3.3.1.jar",
			null
		),
		// New links for current Tomcat version
		new UpgradeSymlink(
			"lib/ecj-4.2.2.jar",
			null,
			"../../../opt/apache-tomcat-6.0/lib/ecj-4.2.2.jar"
		)
	};

	/**
	 * Upgrades the Tomcat 6.0.X installed in the provided directory.
	 */
	boolean upgradeTomcatDirectory(UnixFile tomcatDirectory, int uid, int gid) throws IOException, SQLException {
		boolean needsRestart = false;
		OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
		if(osConfig==OperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64) {
			// Tomcat 6.0
			for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_6_0) {
				if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
			}
			// MySQL
			for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_MySQL) {
				if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
			}
			// PostgreSQL
			for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_PostgreSQL) {
				if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
			}
			// Update bin/profile
			// TODO
			/*
			String results = AOServDaemon.execAndCapture(
				new String[] {
					osConfig.getReplaceCommand(),
					"/aoserv.sh",
					"/aoserv-client.sh",
					"php-4.3.sh",
					"php-4.sh",
					"php-4.3.3.sh",
					"php-4.sh",
					"php-4.3.8.sh",
					"php-4.sh",
					"postgresql-7.3.3.sh",
					"postgresql-7.3.sh",
					"export \"CLASSPATH=/usr/aoserv/lib-1.3/aocode-public.jar:$CLASSPATH\".d/001_aoserv.sh",
					"export \"CLASSPATH=/opt/aoserv-client/lib-1.3/aocode-public.jar:$CLASSPATH\"",
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
