/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2016, 2017, 2018, 2020, 2021, 2022  AO Industries, Inc.
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
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.UpgradeSymlink;
import java.io.IOException;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Set;

/**
 * Some common code for Tomcat 8.0.X
 *
 * @author  AO Industries, Inc.
 */
final class TomcatCommon_8_0_X extends TomcatCommon {

  private static final TomcatCommon_8_0_X instance = new TomcatCommon_8_0_X();

  static TomcatCommon_8_0_X getInstance() {
    return instance;
  }

  private TomcatCommon_8_0_X() {
    // Do nothing
  }

  @Override
  protected Set<PackageManager.PackageName> getRequiredPackages() throws IOException, SQLException {
    return EnumSet.of(
        OperatingSystemConfiguration.getOperatingSystemConfiguration().getDefaultJdkPackageName(),
        PackageManager.PackageName.APACHE_TOMCAT_8_0
    );
  }

  // TODO: This implementation is incorrect for DBCP2 included in Tomcat 8.0.
  //       Tomcat 8.0 is end-of-life and we're migrating clients off it, so
  //       we're not fixing this implementation.
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
   * Upgrades the Tomcat 8.0.X installed in the provided directory.
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
      String rpmVersion = PackageManager.getInstalledPackage(PackageManager.PackageName.APACHE_TOMCAT_8_0).getVersion().toString();
      if (rpmVersion.equals("8.0.30")) {
        // Nothing to do
      } else if (rpmVersion.equals("8.0.32")) {
        UpgradeSymlink[] upgradeSymlinks_8_0_32 = {
            new UpgradeSymlink(
                "lib/postgresql-9.4.1207.jar",
                "../" + optSlash + "apache-tomcat-8.0/lib/postgresql-9.4.1207.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/postgresql-9.4.1208.jar",
                null,
                "../" + optSlash + "apache-tomcat-8.0/lib/postgresql-9.4.1208.jar"
            )
        };
        for (UpgradeSymlink upgradeSymlink : upgradeSymlinks_8_0_32) {
          if (upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      } else if (rpmVersion.equals("8.0.43")) {
        UpgradeSymlink[] upgradeSymlinks_8_0_43 = {
            // ecj-4.4.2.jar -> ecj-4.6.1.jar
            new UpgradeSymlink(
                "lib/ecj-4.4.2.jar",
                "../" + optSlash + "apache-tomcat-8.0/lib/ecj-4.4.2.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/ecj-4.6.1.jar",
                null,
                "../" + optSlash + "apache-tomcat-8.0/lib/ecj-4.6.1.jar"
            ),
            // ecj-4.6.1.jar.jar -> ecj-4.6.1.jar (unwhoops!)
            new UpgradeSymlink(
                "lib/ecj-4.6.1.jar",
                "../" + optSlash + "apache-tomcat-8.0/lib/ecj-4.6.1.jar.jar",
                "../" + optSlash + "apache-tomcat-8.0/lib/ecj-4.6.1.jar"
            ),
            // mysql-connector-java-5.1.38-bin.jar -> mysql-connector-java-5.1.42-bin.jar
            // mysql-connector-java-5.1.41-bin.jar -> mysql-connector-java-5.1.42-bin.jar
            new UpgradeSymlink(
                "lib/mysql-connector-java-5.1.38-bin.jar",
                "../" + optSlash + "apache-tomcat-8.0/lib/mysql-connector-java-5.1.38-bin.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/mysql-connector-java-5.1.41-bin.jar",
                "../" + optSlash + "apache-tomcat-8.0/lib/mysql-connector-java-5.1.41-bin.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/mysql-connector-java-5.1.42-bin.jar",
                null,
                "../" + optSlash + "apache-tomcat-8.0/lib/mysql-connector-java-5.1.42-bin.jar"
            ),
            // postgresql-9.4.1208.jar -> postgresql-42.1.1.jar
            // postgresql-42.0.0.jar -> postgresql-42.1.1.jar
            new UpgradeSymlink(
                "lib/postgresql-9.4.1208.jar",
                "../" + optSlash + "apache-tomcat-8.0/lib/postgresql-9.4.1208.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.0.0.jar",
                "../" + optSlash + "apache-tomcat-8.0/lib/postgresql-42.0.0.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.1.1.jar",
                null,
                "../" + optSlash + "apache-tomcat-8.0/lib/postgresql-42.1.1.jar"
            )
        };
        for (UpgradeSymlink upgradeSymlink : upgradeSymlinks_8_0_43) {
          if (upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      } else if (rpmVersion.equals("8.0.44")) {
        UpgradeSymlink[] upgradeSymlinks_8_0_44 = {
            // ecj-4.6.1.jar -> ecj-4.6.3.jar
            new UpgradeSymlink(
                "lib/ecj-4.6.1.jar",
                "../" + optSlash + "apache-tomcat-8.0/lib/ecj-4.6.1.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/ecj-4.6.3.jar",
                null,
                "../" + optSlash + "apache-tomcat-8.0/lib/ecj-4.6.3.jar"
            )
        };
        for (UpgradeSymlink upgradeSymlink : upgradeSymlinks_8_0_44) {
          if (upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      } else if (
          rpmVersion.equals("8.0.45")
              || rpmVersion.equals("8.0.46")
              || rpmVersion.equals("8.0.47")
              || rpmVersion.equals("8.0.48")
      ) {
        UpgradeSymlink[] upgradeSymlinks_8_0_45 = {
            // mysql-connector-java-5.1.42-bin.jar -> mysql-connector-java-5.1.45-bin.jar
            // mysql-connector-java-5.1.43-bin.jar -> mysql-connector-java-5.1.45-bin.jar
            // mysql-connector-java-5.1.44-bin.jar -> mysql-connector-java-5.1.45-bin.jar
            new UpgradeSymlink(
                "lib/mysql-connector-java-5.1.42-bin.jar",
                "../" + optSlash + "apache-tomcat-8.0/lib/mysql-connector-java-5.1.42-bin.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/mysql-connector-java-5.1.43-bin.jar",
                "../" + optSlash + "apache-tomcat-8.0/lib/mysql-connector-java-5.1.43-bin.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/mysql-connector-java-5.1.44-bin.jar",
                "../" + optSlash + "apache-tomcat-8.0/lib/mysql-connector-java-5.1.44-bin.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/mysql-connector-java-5.1.45-bin.jar",
                null,
                "../" + optSlash + "apache-tomcat-8.0/lib/mysql-connector-java-5.1.45-bin.jar"
            ),
            // postgresql-42.1.1.jar -> postgresql-42.2.0.jar
            // postgresql-42.1.4.jar -> postgresql-42.2.0.jar
            new UpgradeSymlink(
                "lib/postgresql-42.1.1.jar",
                "../" + optSlash + "apache-tomcat-8.0/lib/postgresql-42.1.1.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.1.4.jar",
                "../" + optSlash + "apache-tomcat-8.0/lib/postgresql-42.1.4.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.0.jar",
                null,
                "../" + optSlash + "apache-tomcat-8.0/lib/postgresql-42.2.0.jar"
            )
        };
        for (UpgradeSymlink upgradeSymlink : upgradeSymlinks_8_0_45) {
          if (upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      } else if (
          rpmVersion.equals("8.0.49")
              || rpmVersion.equals("8.0.50")
              || rpmVersion.equals("8.0.51")
              || rpmVersion.equals("8.0.52")
      ) {
        UpgradeSymlink[] upgradeSymlinks_8_0_49 = {
            // mysql-connector-java-5.1.45-bin.jar -> mysql-connector-java-5.1.46-bin.jar
            new UpgradeSymlink(
                "lib/mysql-connector-java-5.1.45-bin.jar",
                "../" + optSlash + "apache-tomcat-8.0/lib/mysql-connector-java-5.1.45-bin.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/mysql-connector-java-5.1.46-bin.jar",
                null,
                "../" + optSlash + "apache-tomcat-8.0/lib/mysql-connector-java-5.1.46-bin.jar"
            ),
            // postgresql-42.2.0.jar -> postgresql-42.2.2.jar
            // postgresql-42.2.1.jar -> postgresql-42.2.1.jar
            new UpgradeSymlink(
                "lib/postgresql-42.2.0.jar",
                "../" + optSlash + "apache-tomcat-8.0/lib/postgresql-42.2.0.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.1.jar",
                "../" + optSlash + "apache-tomcat-8.0/lib/postgresql-42.2.1.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.2.jar",
                null,
                "../" + optSlash + "apache-tomcat-8.0/lib/postgresql-42.2.2.jar"
            )
        };
        for (UpgradeSymlink upgradeSymlink : upgradeSymlinks_8_0_49) {
          if (upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) {
            needsRestart = true;
          }
        }
      } else if (
          rpmVersion.equals("8.0.53")
      ) {
        UpgradeSymlink[] upgradeSymlinks_8_0_53 = {
            // mysql-connector-java-5.1.46-bin.jar -> mysql-connector-java-5.1.47-bin.jar
            new UpgradeSymlink(
                "lib/mysql-connector-java-5.1.46-bin.jar",
                "../" + optSlash + "apache-tomcat-8.0/lib/mysql-connector-java-5.1.46-bin.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/mysql-connector-java-5.1.47-bin.jar",
                null,
                "../" + optSlash + "apache-tomcat-8.0/lib/mysql-connector-java-5.1.47-bin.jar"
            ),
            // postgresql-42.2.2.jar -> postgresql-42.2.5.jar
            // postgresql-42.2.4.jar -> postgresql-42.2.5.jar
            new UpgradeSymlink(
                "lib/postgresql-42.2.2.jar",
                "../" + optSlash + "apache-tomcat-8.0/lib/postgresql-42.2.2.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.4.jar",
                "../" + optSlash + "apache-tomcat-8.0/lib/postgresql-42.2.4.jar",
                null
            ),
            new UpgradeSymlink(
                "lib/postgresql-42.2.5.jar",
                null,
                "../" + optSlash + "apache-tomcat-8.0/lib/postgresql-42.2.5.jar"
            )
        };
        for (UpgradeSymlink upgradeSymlink : upgradeSymlinks_8_0_53) {
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
    return "apache-tomcat-8.0";
  }
}
