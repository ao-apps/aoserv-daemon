/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2018, 2019, 2020, 2021, 2022, 2023, 2024  AO Industries, Inc.
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
import com.aoapps.hodgepodge.util.Tuple2;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.lang.util.CalendarUtils;
import com.aoindustries.aoserv.client.web.tomcat.ContextDataSource;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Objects;

/**
 * Some common code for all installations of Tomcat 8.5 and above.
 *
 * @author  AO Industries, Inc.
 */
public abstract class VersionedTomcatCommon extends TomcatCommon {

  private static final String BACKUP_DATE_SEPARATOR = "-";
  public static final String BACKUP_SEPARATOR = ".";
  public static final String BACKUP_EXTENSION = ".bak";

  /**
   * Gets the suffix to put after an existing file, but before the extension.
   */
  public static String getBackupSuffix() {
    return BACKUP_DATE_SEPARATOR + CalendarUtils.formatDate(new GregorianCalendar());
  }

  public static final int KILL_DELAY_ATTEMPTS = 50;
  public static final float KILL_DELAY_INTERVAL = 0.1f;

  VersionedTomcatCommon() {
    // Do nothing
  }

  /**
   * {@inheritDoc}
   * <p>
   * See:
   * </p>
   * <ol>
   * <li><a href="https://commons.apache.org/proper/commons-dbcp/configuration.html">DBCP – BasicDataSource Configuration</a></li>
   * <li>Tomcat 8.5:
   *   <ol type="a">
   *   <li><a href="https://tomcat.apache.org/tomcat-8.5-doc/jndi-datasource-examples-howto.html">JNDI Datasource HOW-TO</a></li>
   *   <li><a href="https://tomcat.apache.org/tomcat-8.5-doc/jdbc-pool.html">The Tomcat JDBC Connection Pool</a></li>
   *   </ol>
   * </li>
   * <li>Tomcat 9.0:
   *   <ol type="a">
   *   <li><a href="https://tomcat.apache.org/tomcat-9.0-doc/jndi-datasource-examples-howto.html">JNDI Datasource HOW-TO</a></li>
   *   <li><a href="https://tomcat.apache.org/tomcat-9.0-doc/jdbc-pool.html">The Tomcat JDBC Connection Pool</a></li>
   *   </ol>
   * </li>
   * <li>Tomcat 10.0:
   *   <ol type="a">
   *   <li><a href="https://tomcat.apache.org/tomcat-10.0-doc/jndi-datasource-examples-howto.html">JNDI Datasource HOW-TO</a></li>
   *   <li><a href="https://tomcat.apache.org/tomcat-10.0-doc/jdbc-pool.html">The Tomcat JDBC Connection Pool</a></li>
   *   </ol>
   * </li>
   * <li>Tomcat 10.1:
   *   <ol type="a">
   *   <li><a href="https://tomcat.apache.org/tomcat-10.1-doc/jndi-datasource-examples-howto.html">JNDI Datasource HOW-TO</a></li>
   *   <li><a href="https://tomcat.apache.org/tomcat-10.1-doc/jdbc-pool.html">The Tomcat JDBC Connection Pool</a></li>
   *   </ol>
   * </li>
   * </ol>
   */
  @Override
  public void writeHttpdTomcatDataSource(ContextDataSource dataSource, ChainWriter out) throws IOException, SQLException {
    final int maxActive = dataSource.getMaxActive();
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
        + "            maxTotal=\"").textInXmlAttribute(maxActive).print("\"\n"
        + "            maxIdle=\"").textInXmlAttribute(dataSource.getMaxIdle()).print("\"\n"
        + "            maxWaitMillis=\"").textInXmlAttribute(dataSource.getMaxWait()).print("\"\n");
    if (dataSource.getValidationQuery() != null) {
      out.print("            validationQuery=\"").textInXmlAttribute(dataSource.getValidationQuery()).print("\"\n"
          + "            validationQueryTimeout=\"30\"\n"
          + "            testWhileIdle=\"true\"\n");
      // The default is "true": + "            testOnBorrow=\"true\"\n");
    }
    int timeBetweenEvictionRunsMillis = 30000; // Clean every 30 seconds
    int numTestsPerEvictionRun;
    if (maxActive > 0) {
      numTestsPerEvictionRun = maxActive / 4; // Clean up to a quarter of the pool all at once
      if (numTestsPerEvictionRun < 3) {
        // 3 is the default
        numTestsPerEvictionRun = 3;
      }
    } else {
      numTestsPerEvictionRun = 50;
    }

    out.print("            timeBetweenEvictionRunsMillis=\"").textInXmlAttribute(timeBetweenEvictionRunsMillis).print("\"\n"
        + "            numTestsPerEvictionRun=\"").textInXmlAttribute(numTestsPerEvictionRun).print("\"\n"
        + "            removeAbandonedOnMaintenance=\"true\"\n"
        + "            removeAbandonedOnBorrow=\"true\"\n"
        // The default is 300: + "            removeAbandonedTimeout=\"300\"\n"
        // Disabled to avoid overhead: Default is "false": + "            logAbandoned=\"true\"\n"
        + "          />\n");
  }

  protected static byte[] generateProfileCatalinaSh(String optSlash, String apacheTomcatDir, PosixFile installDir) throws IOException {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
      out.print("export CATALINA_BASE=\"").print(installDir).print("\"\n"
          + "export CATALINA_HOME=\"").print(installDir).print("\"\n"
          + "export CATALINA_TEMP=\"").print(installDir).print("/temp\"\n"
          + "\n"
          + "export PATH=\"$PATH:").print(installDir).print("/bin\"\n");
    }
    return bout.toByteArray();
  }

  protected static byte[] generateProfileJavaDisableUsageTrackingSh(String optSlash, String apacheTomcatDir, PosixFile installDir) throws IOException {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
      out.print("export JAVA_OPTS=\"${JAVA_OPTS:-}${JAVA_OPTS+ }-Djdk.disableLastUsageTracking=true\"\n");
    }
    return bout.toByteArray();
  }

  protected static byte[] generateProfileJavaHeadlessSh(String optSlash, String apacheTomcatDir, PosixFile installDir) throws IOException {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
      out.print("export JAVA_OPTS=\"${JAVA_OPTS:-}${JAVA_OPTS+ }-Djava.awt.headless=true\"\n");
    }
    return bout.toByteArray();
  }

  protected static byte[] generateProfileJavaHeapsizeSh(String optSlash, String apacheTomcatDir, PosixFile installDir) throws IOException {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
      out.print("export JAVA_OPTS=\"${JAVA_OPTS:-}${JAVA_OPTS+ }-Xmx128M\"\n");
    }
    return bout.toByteArray();
  }

  // Java 18: Add "-Djava.security.manager=allow" by default for Tomcat?

  protected static byte[] generateProfileJavaServerSh(String optSlash, String apacheTomcatDir, PosixFile installDir) throws IOException {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
      out.print("export JAVA_OPTS=\"${JAVA_OPTS:-}${JAVA_OPTS+ }-server\"\n");
    }
    return bout.toByteArray();
  }

  protected static String generateProfileJdkShTarget(String optSlash) throws IOException, SQLException {
    final OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
    String jdkProfileSh = osConfig.getDefaultJdkProfileSh().toString();
    if (!jdkProfileSh.startsWith("/opt/")) {
      throw new IllegalStateException("jdkProfileSh does not start with \"/opt/\": " + jdkProfileSh);
    }
    return "../../" + optSlash + jdkProfileSh.substring("/opt/".length());
  }

  protected static byte[] generateProfileUmaskSh(String optSlash, String apacheTomcatDir, PosixFile installDir) {
    return (
        "umask 0027\n"
            + "export UMASK=0027\n"
    ).getBytes(StandardCharsets.UTF_8);
  }

  protected static byte[] generateShutdownSh(String optSlash, String apacheTomcatDir, PosixFile installDir) throws IOException {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
      out.print("#!/bin/sh\n"
          + "#\n"
          + "# Generated by ").print(VersionedTomcatCommon.class.getName()).print("\n"
          + "#\n"
          + "exec \"").print(installDir).print("/bin/tomcat\" stop\n");
    }
    return bout.toByteArray();
  }

  protected static byte[] generateStartupSh(String optSlash, String apacheTomcatDir, PosixFile installDir) throws IOException {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
      out.print("#!/bin/sh\n"
          + "#\n"
          + "# Generated by ").print(VersionedTomcatCommon.class.getName()).print("\n"
          + "#\n"
          + "exec \"").print(installDir).print("/bin/tomcat\" start\n");
    }
    return bout.toByteArray();
  }

  /**
   * Gets the set of files that are installed during install and upgrade/downgrade.
   * Each path is relative to CATALINA_HOME/CATALINA_BASE.
   */
  protected abstract List<Install> getInstallFiles(String optSlash, PosixFile installDir, int confMode) throws IOException, SQLException;

  /**
   * Combines version and release for simpler comparison.
   */
  static class Version extends Tuple2<PackageManager.Version, PackageManager.Version> implements Comparable<Version> {

    private static final char SEPARATOR = '-';

    Version(PackageManager.Version version, PackageManager.Version release) {
      super(version, release);
    }

    @Override
    public String toString() {
      return Objects.toString(getElement1()) + SEPARATOR + Objects.toString(getElement2());
    }

    @Override
    public int compareTo(Version o) {
      int diff = getElement1().compareTo(o.getElement1());
      if (diff != 0) {
        return diff;
      }
      return getElement2().compareTo(o.getElement2());
    }

    /**
     * Compares to the given version and release, separated by last {@literal #SEPARATOR}.
     */
    public int compareTo(String versionAndRelase) {
      int last = versionAndRelase.lastIndexOf(SEPARATOR);
      if (last == -1) {
        throw new IllegalArgumentException("No hyphen in combined version and release: " + versionAndRelase);
      }
      return compareTo(
          new Version(
              new PackageManager.Version(versionAndRelase.substring(0, last)),
              new PackageManager.Version(versionAndRelase.substring(last + 1))
          )
      );
    }
  }

  /**
   * Gets the version of the installed RPM.
   *
   * @param oldPackageName (Optional) the old name for the package before changing to aorepo.org.
   */
  protected Version getRpmVersion(PackageManager.PackageName packageName, PackageManager.PackageName oldPackageName)
      throws IOException {
    PackageManager.Rpm rpm = PackageManager.getInstalledPackage(packageName);
    if (rpm == null && oldPackageName != null) {
      rpm = PackageManager.getInstalledPackage(oldPackageName);
    }
    if (rpm == null) {
      if (oldPackageName == null) {
        throw new AssertionError("Package not installed: " + packageName);
      } else {
        throw new AssertionError("Package not installed: " + packageName
            + " or " + oldPackageName);
      }
    }
    return new Version(rpm.getVersion(), rpm.getRelease());
  }

  /**
   * Upgrades the Tomcat installed in the provided directory.
   *
   * @param optSlash  Relative path from the CATALINA_HOME to /opt/, including trailing slash, such as <code>../../opt/</code>.
   */
  protected abstract boolean upgradeTomcatDirectory(String optSlash, PosixFile tomcatDirectory, int uid, int gid) throws IOException, SQLException;

  static enum OpenSslLifecycleType {
    /**
     * This was used for all of Tomcat 8.5, Tomcat 9.0 through 9.0.91, all of Tomcat 10.0, and Tomcat 10.1 through 10.1.16.
     */
    TOMCAT_8_5 {
      @Override
      void printOpenSslLifecycleListener(ChainWriter out) {
        out.print("  <!--APR library loader. Documentation at /docs/apr.html -->\n"
            + "  <Listener className=\"org.apache.catalina.core.AprLifecycleListener\" SSLEngine=\"on\" />\n");
      }
    },

    /**
     * This was added between Tomcat 9.0.91 and 9.0.93.
     */
    TOMCAT_9_0_93 {
      @Override
      void printOpenSslLifecycleListener(ChainWriter out) {
        out.print("  <!-- APR connector and OpenSSL support using Tomcat Native -->\n"
            + "  <Listener className=\"org.apache.catalina.core.AprLifecycleListener\" />\n"
            + "  <!-- OpenSSL support using FFM API from Java 22 -->\n"
            + "  <!-- <Listener className=\"org.apache.catalina.core.OpenSSLLifecycleListener\" /> -->\n");
      }
    },

    /**
     * This was added between Tomcat 10.1.16 and 10.1.24.
     */
    TOMCAT_10_1_24 {
      @Override
      void printOpenSslLifecycleListener(ChainWriter out) {
        out.print("  <!-- OpenSSL support using Tomcat Native -->\n"
            + "  <Listener className=\"org.apache.catalina.core.AprLifecycleListener\" />\n"
            + "  <!-- OpenSSL support using FFM API from Java 22 -->\n"
            + "  <!-- <Listener className=\"org.apache.catalina.core.OpenSSLLifecycleListener\" /> -->\n");
      }
    };

    /**
     * Prints the OpenSSL support section of the server.xml.
     */
    abstract void printOpenSslLifecycleListener(ChainWriter out);
  }

  /**
   * Gets the type of org.apache.catalina.core.OpenSSLLifecycleListener for this version.
   */
  abstract OpenSslLifecycleType getOpenSslLifecycleType() throws IOException, SQLException;
}
