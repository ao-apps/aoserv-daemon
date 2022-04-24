/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2013, 2015, 2016, 2017, 2018, 2019, 2020, 2021, 2022  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of aoserv-daemon.
 *
 * aoserv-daemon is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aoserv-daemon is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with aoserv-daemon.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoapps.encoding.ChainWriter;
import com.aoapps.io.posix.PosixFile;
import com.aoindustries.aoserv.client.web.tomcat.ContextDataSource;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.UpgradeSymlink;
import java.io.IOException;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Set;

/**
 * Some common code for Tomcat 7.0.X
 *
 * @author  AO Industries, Inc.
 */
final class TomcatCommon_7_0_X extends TomcatCommon {

  private static final TomcatCommon_7_0_X instance = new TomcatCommon_7_0_X();

  static TomcatCommon_7_0_X getInstance() {
    return instance;
  }

  private TomcatCommon_7_0_X() {
    // Do nothing
  }

  @Override
  protected Set<PackageManager.PackageName> getRequiredPackages() throws IOException, SQLException {
    return EnumSet.of(
        OperatingSystemConfiguration.getOperatingSystemConfiguration().getDefaultJdkPackageName(),
        PackageManager.PackageName.APACHE_TOMCAT_7_0
    );
  }

  @Override
  public void writeHttpdTomcatDataSource(ContextDataSource dataSource, ChainWriter out) throws SQLException, IOException {
    out.print("          <Resource\n"
        + "            name=\"").textInXmlAttribute(dataSource.getName()).print("\"\n"
        + "            auth=\"Container\"\n"
        + "            type=\"javax.sql.DataSource\"\n");
    String username = dataSource.getUsername();
    if (username != null && !username.isEmpty()) {
      out.print("            username=\"").textInXmlAttribute(username).print("\"\n");
    }
    String password = dataSource.getPassword();
    if (password != null && !password.isEmpty()) {
      out.print("            password=\"").textInXmlAttribute(password).print("\"\n");
    }
    String driverClassName = dataSource.getDriverClassName();
    if (driverClassName != null && !driverClassName.isEmpty()) {
      out.print("            driverClassName=\"").textInXmlAttribute(driverClassName).print("\"\n");
    }
    out.print("            url=\"").textInXmlAttribute(dataSource.getUrl()).print("\"\n"
        + "            maxActive=\"").textInXmlAttribute(dataSource.getMaxActive()).print("\"\n"
        + "            maxIdle=\"").textInXmlAttribute(dataSource.getMaxIdle()).print("\"\n"
        + "            maxWait=\"").textInXmlAttribute(dataSource.getMaxWait()).print("\"\n");
    if (dataSource.getValidationQuery() != null) {
      out.print("            validationQuery=\"").textInXmlAttribute(dataSource.getValidationQuery()).print("\"\n");
    }
    out.print("            removeAbandoned=\"true\"\n"
        + "            removeAbandonedTimeout=\"300\"\n"
        + "            logAbandoned=\"true\"\n"
        + "          />\n");
  }

  /**
   * Upgrades the Tomcat 7.0.X installed in the provided directory.
   *
   * @param optSlash  Relative path from the CATALINA_HOME to /opt/, including trailing slash, such as <code>../../opt/</code>.
   */
  boolean upgradeTomcatDirectory(String optSlash, PosixFile tomcatDirectory, int uid, int gid) throws IOException, SQLException {
    boolean needsRestart = false;
    OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
    if (
        osConfig == OperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64
            || osConfig == OperatingSystemConfiguration.CENTOS_7_X86_64
    ) {
      String rpmVersion = PackageManager.getInstalledPackage(PackageManager.PackageName.APACHE_TOMCAT_7_0).getVersion().toString();
      if (rpmVersion.equals("7.0.42")) {
        // Nothing to do
      } else if (rpmVersion.equals("7.0.68")) {
        UpgradeSymlink[] upgradeSymlinks_7_0_68 = {
            new UpgradeSymlink(
                "lib/ecj-4.2.2.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/ecj-4.2.2.jar",
                "lib/ecj-4.4.2.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/ecj-4.4.2.jar"
            ),
            new UpgradeSymlink(
                "lib/mysql-connector-java-5.1.25-bin.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.25-bin.jar",
                "lib/mysql-connector-java-5.1.38-bin.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.38-bin.jar"
            ),
            new UpgradeSymlink(
                "lib/postgresql-9.2-1003.jdbc4.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-9.2-1003.jdbc4.jar",
                "lib/postgresql-9.4.1208.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-9.4.1208.jre6.jar"
            ),
            new UpgradeSymlink(
                "lib/tomcat7-websocket.jar",
                null,
                "../" + optSlash + "apache-tomcat-7.0/lib/tomcat7-websocket.jar"
            ),
            new UpgradeSymlink(
                "lib/websocket-api.jar",
                null,
                "../" + optSlash + "apache-tomcat-7.0/lib/websocket-api.jar"
            )
        };
        for (UpgradeSymlink upgradeSymlink : upgradeSymlinks_7_0_68) {
          if (upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
        if (osConfig == OperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64) {
          // Switch from Java 1.7 to Java 1.* now that compatible with Java 1.8
          String results = AOServDaemon.execAndCapture(
              new String[]{
                  osConfig.getReplaceCommand().toString(),
                  "/opt/jdk1.7-i686/setenv.sh",
                  "/opt/jdk1-i686/setenv.sh",
                  "--",
                  tomcatDirectory.getPath() + "/bin/profile"
              }
          );
          if (results.length() > 0) {
            needsRestart = true;
          }
        }
      } else if (
          rpmVersion.equals("7.0.77")
              || rpmVersion.equals("7.0.78")
              || rpmVersion.equals("7.0.79")
              || rpmVersion.equals("7.0.81")
              || rpmVersion.equals("7.0.82")
      ) {
        UpgradeSymlink[] upgradeSymlinks_7_0_77 = {
            // mysql-connector-java-5.1.38-bin.jar -> mysql-connector-java-5.1.45-bin.jar
            // mysql-connector-java-5.1.41-bin.jar -> mysql-connector-java-5.1.45-bin.jar
            // mysql-connector-java-5.1.42-bin.jar -> mysql-connector-java-5.1.45-bin.jar
            // mysql-connector-java-5.1.43-bin.jar -> mysql-connector-java-5.1.45-bin.jar
            // mysql-connector-java-5.1.44-bin.jar -> mysql-connector-java-5.1.45-bin.jar
            new UpgradeSymlink(
                "lib/mysql-connector-java-5.1.38-bin.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.38-bin.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/mysql-connector-java-5.1.41-bin.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.41-bin.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/mysql-connector-java-5.1.42-bin.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.42-bin.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/mysql-connector-java-5.1.43-bin.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.43-bin.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/mysql-connector-java-5.1.44-bin.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.44-bin.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/mysql-connector-java-5.1.45-bin.jar",
                null,
                "../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.45-bin.jar"
            ),
            // postgresql-9.4.1208.jre6.jar -> postgresql-42.2.0.jre6.jar
            // postgresql-42.0.0.jre6.jar -> postgresql-42.2.0.jre6.jar
            // postgresql-42.1.1.jre6.jar -> postgresql-42.2.0.jre6.jar
            // postgresql-42.1.4.jre6.jar -> postgresql-42.2.0.jre6.jar
            new UpgradeSymlink(
                "lib/postgresql-9.4.1208.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-9.4.1208.jre6.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.0.0.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.0.0.jre6.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.1.1.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.1.1.jre6.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.1.4.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.1.4.jre6.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.0.jre6.jar",
                null,
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.0.jre6.jar"
            )
        };
        for (UpgradeSymlink upgradeSymlink : upgradeSymlinks_7_0_77) {
          if (upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      } else if (
          rpmVersion.equals("7.0.84")
              || rpmVersion.equals("7.0.85")
              || rpmVersion.equals("7.0.86")
              || rpmVersion.equals("7.0.88")
      ) {
        UpgradeSymlink[] upgradeSymlinks_7_0_84 = {
            // mysql-connector-java-5.1.45-bin.jar -> mysql-connector-java-5.1.46-bin.jar
            new UpgradeSymlink(
                "lib/mysql-connector-java-5.1.45-bin.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.45-bin.jar",
                "lib/mysql-connector-java-5.1.46-bin.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.46-bin.jar"
            ),
            // postgresql-42.2.0.jre6.jar -> postgresql-42.2.2.jre6.jar
            // postgresql-42.2.1.jre6.jar -> postgresql-42.2.1.jre6.jar
            new UpgradeSymlink(
                "lib/postgresql-42.2.0.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.0.jre6.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.1.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.1.jre6.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.2.jre6.jar",
                null,
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.2.jre6.jar"
            )
        };
        for (UpgradeSymlink upgradeSymlink : upgradeSymlinks_7_0_84) {
          if (upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      } else if (rpmVersion.equals("7.0.90")) {
        UpgradeSymlink[] upgradeSymlinks_7_0_90 = {
            // mysql-connector-java-5.1.46-bin.jar -> mysql-connector-java-5.1.47-bin.jar
            new UpgradeSymlink(
                "lib/mysql-connector-java-5.1.46-bin.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.46-bin.jar",
                "lib/mysql-connector-java-5.1.47-bin.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.47-bin.jar"
            ),
            // postgresql-42.2.2.jre6.jar -> postgresql-42.2.5.jre6.jar
            // postgresql-42.2.4.jre6.jar -> postgresql-42.2.5.jre6.jar
            new UpgradeSymlink(
                "lib/postgresql-42.2.2.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.2.jre6.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.4.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.4.jre6.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.5.jre6.jar",
                null,
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.5.jre6.jar"
            )
        };
        for (UpgradeSymlink upgradeSymlink : upgradeSymlinks_7_0_90) {
          if (upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      } else if (
          rpmVersion.equals("7.0.91")
              || rpmVersion.equals("7.0.92")
              || rpmVersion.equals("7.0.93")
              || rpmVersion.equals("7.0.94")
      ) {
        UpgradeSymlink[] upgradeSymlinks_7_0_91 = {
            new UpgradeSymlink(
                "lib/tomcat-i18n-ru.jar",
                null,
                "../" + optSlash + "apache-tomcat-7.0/lib/tomcat-i18n-ru.jar"
            ),
            // postgresql-42.2.5.jre6.jar -> postgresql-42.2.6.jre6.jar
            new UpgradeSymlink(
                "lib/postgresql-42.2.5.jre6.jar",
                "/dev/null",
                "lib/postgresql-42.2.6.jre6.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.5.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.5.jre6.jar",
                "lib/postgresql-42.2.6.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.6.jre6.jar"
            )
        };
        for (UpgradeSymlink upgradeSymlink : upgradeSymlinks_7_0_91) {
          if (upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      } else if (rpmVersion.equals("7.0.96")) {
        UpgradeSymlink[] upgradeSymlinks_7_0_96 = {
            // mysql-connector-java-5.1.47-bin.jar -> mysql-connector-java-5.1.48-bin.jar
            new UpgradeSymlink(
                "lib/mysql-connector-java-5.1.47-bin.jar",
                "/dev/null",
                "lib/mysql-connector-java-5.1.48-bin.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/mysql-connector-java-5.1.47-bin.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.47-bin.jar",
                "lib/mysql-connector-java-5.1.48-bin.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.48-bin.jar"
            ),
            // postgresql-42.2.6.jre6.jar -> postgresql-42.2.8.jre6.jar
            new UpgradeSymlink(
                "lib/postgresql-42.2.6.jre6.jar",
                "/dev/null",
                "lib/postgresql-42.2.8.jre6.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.6.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.6.jre6.jar",
                "lib/postgresql-42.2.8.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.8.jre6.jar"
            ),
            // postgresql-42.2.8.jre6.jar -> postgresql-42.2.9.jre6.jar
            new UpgradeSymlink(
                "lib/postgresql-42.2.8.jre6.jar",
                "/dev/null",
                "lib/postgresql-42.2.9.jre6.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.8.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.8.jre6.jar",
                "lib/postgresql-42.2.9.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.9.jre6.jar"
            ),
        };
        for (UpgradeSymlink upgradeSymlink : upgradeSymlinks_7_0_96) {
          if (upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      } else if (rpmVersion.equals("7.0.99")) {
        UpgradeSymlink[] upgradeSymlinks_7_0_99 = {
            // New conf/tomcat-users.xsd
            new UpgradeSymlink(
                "conf/tomcat-users.xsd",
                null,
                "../" + optSlash + "apache-tomcat-7.0/conf/tomcat-users.xsd"
            ),
            // New lib/tomcat-i18n-*.jar
            new UpgradeSymlink(
                "lib/tomcat-i18n-de.jar",
                null,
                "../" + optSlash + "apache-tomcat-7.0/lib/tomcat-i18n-de.jar"
            ),
            new UpgradeSymlink(
                "lib/tomcat-i18n-ko.jar",
                null,
                "../" + optSlash + "apache-tomcat-7.0/lib/tomcat-i18n-ko.jar"
            ),
            new UpgradeSymlink(
                "lib/tomcat-i18n-zh-CN.jar",
                null,
                "../" + optSlash + "apache-tomcat-7.0/lib/tomcat-i18n-zh-CN.jar"
            ),
            // postgresql-42.2.8.jre6.jar -> postgresql-42.2.9.jre6.jar
            new UpgradeSymlink(
                "lib/postgresql-42.2.8.jre6.jar",
                "/dev/null",
                "lib/postgresql-42.2.9.jre6.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.8.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.8.jre6.jar",
                "lib/postgresql-42.2.9.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.9.jre6.jar"
            ),
            // postgresql-42.2.9.jre6.jar -> postgresql-42.2.10.jre6.jar
            new UpgradeSymlink(
                "lib/postgresql-42.2.9.jre6.jar",
                "/dev/null",
                "lib/postgresql-42.2.10.jre6.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.9.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.9.jre6.jar",
                "lib/postgresql-42.2.10.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.10.jre6.jar"
            ),
        };
        for (UpgradeSymlink upgradeSymlink : upgradeSymlinks_7_0_99) {
          if (upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      } else if (
          rpmVersion.equals("7.0.100")
              || rpmVersion.equals("7.0.103")
              || rpmVersion.equals("7.0.104")
      ) {
        UpgradeSymlink[] upgradeSymlinks_7_0_100 = {
            // postgresql-42.2.10.jre6.jar -> postgresql-42.2.11.jre6.jar
            new UpgradeSymlink(
                "lib/postgresql-42.2.10.jre6.jar",
                "/dev/null",
                "lib/postgresql-42.2.11.jre6.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.10.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.10.jre6.jar",
                "lib/postgresql-42.2.11.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.11.jre6.jar"
            ),
            // postgresql-42.2.11.jre6.jar -> postgresql-42.2.12.jre6.jar
            new UpgradeSymlink(
                "lib/postgresql-42.2.11.jre6.jar",
                "/dev/null",
                "lib/postgresql-42.2.12.jre6.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.11.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.11.jre6.jar",
                "lib/postgresql-42.2.12.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.12.jre6.jar"
            ),
            // mysql-connector-java-5.1.48-bin.jar -> mysql-connector-java-5.1.49-bin.jar
            new UpgradeSymlink(
                "lib/mysql-connector-java-5.1.48-bin.jar",
                "/dev/null",
                "lib/mysql-connector-java-5.1.49-bin.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/mysql-connector-java-5.1.48-bin.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.48-bin.jar",
                "lib/mysql-connector-java-5.1.49-bin.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/mysql-connector-java-5.1.49-bin.jar"
            ),
            // postgresql-42.2.12.jre6.jar -> postgresql-42.2.13.jre6.jar
            new UpgradeSymlink(
                "lib/postgresql-42.2.12.jre6.jar",
                "/dev/null",
                "lib/postgresql-42.2.13.jre6.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.12.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.12.jre6.jar",
                "lib/postgresql-42.2.13.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.13.jre6.jar"
            ),
        };
        for (UpgradeSymlink upgradeSymlink : upgradeSymlinks_7_0_100) {
          if (upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      } else if (rpmVersion.equals("7.0.105")) {
        UpgradeSymlink[] upgradeSymlinks_7_0_105 = {
            // postgresql-42.2.13.jre6.jar -> postgresql-42.2.14.jre6.jar
            new UpgradeSymlink(
                "lib/postgresql-42.2.13.jre6.jar",
                "/dev/null",
                "lib/postgresql-42.2.14.jre6.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.13.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.13.jre6.jar",
                "lib/postgresql-42.2.14.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.14.jre6.jar"
            ),
            // postgresql-42.2.14.jre6.jar -> postgresql-42.2.16.jre6.jar
            new UpgradeSymlink(
                "lib/postgresql-42.2.14.jre6.jar",
                "/dev/null",
                "lib/postgresql-42.2.16.jre6.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.14.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.14.jre6.jar",
                "lib/postgresql-42.2.16.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.16.jre6.jar"
            ),
        };
        for (UpgradeSymlink upgradeSymlink : upgradeSymlinks_7_0_105) {
          if (upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      } else if (
          rpmVersion.equals("7.0.106")
              || rpmVersion.equals("7.0.107")
      ) {
        UpgradeSymlink[] upgradeSymlinks_7_0_106 = {
            // postgresql-42.2.16.jre6.jar -> postgresql-42.2.17.jre6.jar
            new UpgradeSymlink(
                "lib/postgresql-42.2.16.jre6.jar",
                "/dev/null",
                "lib/postgresql-42.2.17.jre6.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.16.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.16.jre6.jar",
                "lib/postgresql-42.2.17.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.17.jre6.jar"
            ),
            // postgresql-42.2.17.jre6.jar -> postgresql-42.2.18.jre6.jar
            new UpgradeSymlink(
                "lib/postgresql-42.2.17.jre6.jar",
                "/dev/null",
                "lib/postgresql-42.2.18.jre6.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.17.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.17.jre6.jar",
                "lib/postgresql-42.2.18.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.18.jre6.jar"
            ),
        };
        for (UpgradeSymlink upgradeSymlink : upgradeSymlinks_7_0_106) {
          if (upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      } else if (
          rpmVersion.equals("7.0.108")
      ) {
        UpgradeSymlink[] upgradeSymlinks_7_0_108 = {
            // postgresql-42.2.18.jre6.jar -> postgresql-42.2.19.jre6.jar
            new UpgradeSymlink(
                "lib/postgresql-42.2.18.jre6.jar",
                "/dev/null",
                "lib/postgresql-42.2.19.jre6.jar",
                "/dev/null"
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.18.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.18.jre6.jar",
                "lib/postgresql-42.2.19.jre6.jar",
                "../" + optSlash + "apache-tomcat-7.0/lib/postgresql-42.2.19.jre6.jar"
            ),
        };
        for (UpgradeSymlink upgradeSymlink : upgradeSymlinks_7_0_108) {
          if (upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      } else {
        throw new IllegalStateException("Unexpected version of Tomcat: " + rpmVersion);
      }
    }
    return needsRestart;
  }

  @Override
  protected String getApacheTomcatDir() {
    return "apache-tomcat-7.0";
  }
}
