/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2013, 2016, 2017, 2018, 2019, 2020, 2021, 2022, 2023, 2024  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.posix.linux;

import com.aoapps.concurrent.ConcurrentListenerManager;
import com.aoapps.hodgepodge.io.DirectoryMetaSnapshot;
import com.aoapps.lang.Strings;
import com.aoapps.lang.io.function.IORunnable;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.AoservDaemonConfiguration;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the set of packages on a server.
 *
 * @author  AO Industries, Inc.
 */
public final class PackageManager {

  /** Make no instances. */
  private PackageManager() {
    throw new AssertionError();
  }

  private static final Logger logger = Logger.getLogger(PackageManager.class.getName());

  /**
   * The name prefix used to match any Apache Tomcat package installations after the move to AORepo.org.
   */
  public static final String APACHE_TOMCAT_PREFIX = "apache-tomcat-";

  /**
   * The name prefix used to match any Apache Tomcat package installations before the move to AORepo.org.
   */
  public static final String OLD_APACHE_TOMCAT_PREFIX = "apache-tomcat_";

  /**
   * The set of all managed package names.  This is specified as an enum
   * to avoid accidental removal of critical system RPMs from outside callers.
   */
  public enum PackageName {
    APACHE_TOMCAT_3_1(APACHE_TOMCAT_PREFIX + "3.1"),
    APACHE_TOMCAT_3_2(APACHE_TOMCAT_PREFIX + "3.2"),
    APACHE_TOMCAT_4_1(APACHE_TOMCAT_PREFIX + "4.1"),
    APACHE_TOMCAT_5_5(APACHE_TOMCAT_PREFIX + "5.5"),
    APACHE_TOMCAT_6_0(APACHE_TOMCAT_PREFIX + "6.0"),
    OLD_APACHE_TOMCAT_6_0(OLD_APACHE_TOMCAT_PREFIX + "6_0"),
    APACHE_TOMCAT_7_0(APACHE_TOMCAT_PREFIX + "7.0"),
    OLD_APACHE_TOMCAT_7_0(OLD_APACHE_TOMCAT_PREFIX + "7_0"),
    APACHE_TOMCAT_8_0(APACHE_TOMCAT_PREFIX + "8.0"),
    OLD_APACHE_TOMCAT_8_0(OLD_APACHE_TOMCAT_PREFIX + "8_0"),
    APACHE_TOMCAT_8_5(APACHE_TOMCAT_PREFIX + "8.5"),
    OLD_APACHE_TOMCAT_8_5(OLD_APACHE_TOMCAT_PREFIX + "8_5"),
    APACHE_TOMCAT_9_0(APACHE_TOMCAT_PREFIX + "9.0"),
    OLD_APACHE_TOMCAT_9_0(OLD_APACHE_TOMCAT_PREFIX + "9_0"),
    APACHE_TOMCAT_10_0(APACHE_TOMCAT_PREFIX + "10.0"),
    OLD_APACHE_TOMCAT_10_0(OLD_APACHE_TOMCAT_PREFIX + "10_0"),
    APACHE_TOMCAT_10_1(APACHE_TOMCAT_PREFIX + "10.1"),
    OLD_APACHE_TOMCAT_10_1(OLD_APACHE_TOMCAT_PREFIX + "10_1"),
    AOSERV_FTP_SHELLS("aoserv-ftp-shells"),
    AOSERV_HTTPD_CONFIG("aoserv-httpd-config"),
    AOSERV_HTTPD_SITE_DISABLED("aoserv-httpd-site-disabled"),
    AOSERV_IMAPD_CONFIG("aoserv-imapd-config"),
    AOSERV_JILTER("aoserv-jilter"),
    AOSERV_MRTG("aoserv-mrtg"),
    AOSERV_PASSWD_SHELL("aoserv-passwd-shell"),
    AOSERV_PROFILE_D("aoserv-profile.d"),
    AOSERV_SENDMAIL_CONFIG("aoserv-sendmail-config"),
    AWSTATS_6("awstats-6"),
    AWSTATS("awstats"),
    BIND("bind"),
    CA_TRUST_HASH("ca-trust-hash"),
    CACHING_NAMESERVER("caching-nameserver"), // This is a distinct package in CentOS 5, but provided by "bind" in CentOS 7
    CLOUD_INIT("cloud-init"), // Amazon EC2 cloud-init
    CVS("cvs"),
    CYRUS_IMAPD("cyrus-imapd"),
    CYRUS_IMAPD_AFTER_NETWORK_ONLINE("cyrus-imapd-after-network-online"),
    CYRUS_IMAPD_COPY_CERTIFICATES("cyrus-imapd-copy-certificates"),
    CYRUS_SASL("cyrus-sasl"),
    CYRUS_SASL_PLAIN("cyrus-sasl-plain"),
    FAIL2BAN_FILTER_CYRUS_IMAP_MORE_SERVICES("fail2ban-filter-cyrus-imap-more-services"),
    FAIL2BAN_FILTER_SENDMAIL_DISCONNECT("fail2ban-filter-sendmail-disconnect"),
    FAIL2BAN_FIREWALLD("fail2ban-firewalld"),
    FAIL2BAN_SERVER("fail2ban-server"),
    FIREWALLD("firewalld"),
    GZIP("gzip"),
    HAVEGED("haveged"),
    HDDTEMP("hddtemp"),
    HTTPD("httpd"),
    HTTPD_AFTER_NETWORK_ONLINE("httpd-after-network-online"),
    HTTPD_N("httpd-n"),
    HTTPD_TOOLS("httpd-tools"),
    JBOSS_2_2_2("jboss-2.2.2"),
    JDK_LTS("jdk-lts"),
    JDK1_I686("jdk1-i686"),
    JDK17("jdk17"),
    JDK17_I686("jdk17-i686"),
    LIBPCAP("libpcap"),
    LSOF("lsof"),
    MAJORDOMO("majordomo"),
    MOD_SSL("mod_ssl"),
    MOD_WSGI("mod_wsgi"),
    MRTG("mrtg"),
    NET_TOOLS("net-tools"),
    OPENSSH_SERVER("openssh-server"),
    PERL("perl"),
    PERL_FILE_COPY("perl-File-Copy"),
    PERL_INTERPRETER("perl-interpreter"),
    PERL_TIME_HIRES("perl-Time-HiRes"),
    PHP_5_2("php-5.2"),
    PHP_5_2_EXT_MEMCACHED("php-5.2-ext-memcached"),
    PHP_5_2_I686("php-5.2-i686"),
    PHP_5_2_I686_EXT_MEMCACHED("php-5.2-i686-ext-memcached"),
    PHP_5_3("php-5.3"),
    PHP_5_3_EXT_MEMCACHED("php-5.3-ext-memcached"),
    PHP_5_3_I686("php-5.3-i686"),
    PHP_5_3_I686_EXT_MEMCACHED("php-5.3-i686-ext-memcached"),
    PHP_5_4("php-5.4"),
    PHP_5_4_EXT_MEMCACHED("php-5.4-ext-memcached"),
    PHP_5_4_I686("php-5.4-i686"),
    PHP_5_4_I686_EXT_MEMCACHED("php-5.4-i686-ext-memcached"),
    PHP_5_5("php-5.5"),
    PHP_5_5_EXT_MEMCACHED("php-5.5-ext-memcached"),
    PHP_5_5_I686("php-5.5-i686"),
    PHP_5_5_I686_EXT_MEMCACHED("php-5.5-i686-ext-memcached"),
    PHP_5_6("php-5.6"),
    PHP_5_6_EXT_MEMCACHED("php-5.6-ext-memcached"),
    PHP_5_6_I686("php-5.6-i686"),
    PHP_5_6_I686_EXT_MEMCACHED("php-5.6-i686-ext-memcached"),
    PHP_7_0("php-7.0"),
    PHP_7_0_BCMATH("php-7.0-bcmath"),
    PHP_7_0_BZ2("php-7.0-bz2"),
    PHP_7_0_CURL("php-7.0-curl"),
    PHP_7_0_DBA("php-7.0-dba"),
    PHP_7_0_GD("php-7.0-gd"),
    PHP_7_0_GMP("php-7.0-gmp"),
    PHP_7_0_JSON("php-7.0-json"),
    PHP_7_0_MBSTRING("php-7.0-mbstring"),
    PHP_7_0_MCRYPT("php-7.0-mcrypt"),
    PHP_7_0_MYSQL("php-7.0-mysql"),
    PHP_7_0_PGSQL("php-7.0-pgsql"),
    PHP_7_0_PSPELL("php-7.0-pspell"),
    PHP_7_0_SOAP("php-7.0-soap"),
    PHP_7_0_SQLITE3("php-7.0-sqlite3"),
    PHP_7_0_XML("php-7.0-xml"),
    PHP_7_0_XMLRPC("php-7.0-xmlrpc"),
    PHP_7_0_ZIP("php-7.0-zip"),
    PHP_7_0_EXT_HTTP("php-7.0-ext-http"),
    PHP_7_0_EXT_MEMCACHED("php-7.0-ext-memcached"),
    PHP_7_0_EXT_PROPRO("php-7.0-ext-propro"),
    PHP_7_0_EXT_RAPHF("php-7.0-ext-raphf"),
    PHP_7_1("php-7.1"),
    PHP_7_1_BCMATH("php-7.1-bcmath"),
    PHP_7_1_BZ2("php-7.1-bz2"),
    PHP_7_1_CURL("php-7.1-curl"),
    PHP_7_1_DBA("php-7.1-dba"),
    PHP_7_1_GD("php-7.1-gd"),
    PHP_7_1_GMP("php-7.1-gmp"),
    PHP_7_1_JSON("php-7.1-json"),
    PHP_7_1_MBSTRING("php-7.1-mbstring"),
    PHP_7_1_MYSQL("php-7.1-mysql"),
    PHP_7_1_PGSQL("php-7.1-pgsql"),
    PHP_7_1_PSPELL("php-7.1-pspell"),
    PHP_7_1_SOAP("php-7.1-soap"),
    PHP_7_1_SQLITE3("php-7.1-sqlite3"),
    PHP_7_1_XML("php-7.1-xml"),
    PHP_7_1_XMLRPC("php-7.1-xmlrpc"),
    PHP_7_1_ZIP("php-7.1-zip"),
    PHP_7_1_EXT_HTTP("php-7.1-ext-http"),
    PHP_7_1_EXT_MEMCACHED("php-7.1-ext-memcached"),
    PHP_7_1_EXT_PROPRO("php-7.1-ext-propro"),
    PHP_7_1_EXT_RAPHF("php-7.1-ext-raphf"),
    PHP_7_2("php-7.2"),
    PHP_7_2_BCMATH("php-7.2-bcmath"),
    PHP_7_2_BZ2("php-7.2-bz2"),
    PHP_7_2_CURL("php-7.2-curl"),
    PHP_7_2_DBA("php-7.2-dba"),
    PHP_7_2_GD("php-7.2-gd"),
    PHP_7_2_GMP("php-7.2-gmp"),
    PHP_7_2_JSON("php-7.2-json"),
    PHP_7_2_MBSTRING("php-7.2-mbstring"),
    PHP_7_2_MYSQL("php-7.2-mysql"),
    PHP_7_2_PGSQL("php-7.2-pgsql"),
    PHP_7_2_PSPELL("php-7.2-pspell"),
    PHP_7_2_SOAP("php-7.2-soap"),
    PHP_7_2_SQLITE3("php-7.2-sqlite3"),
    PHP_7_2_XML("php-7.2-xml"),
    PHP_7_2_XMLRPC("php-7.2-xmlrpc"),
    PHP_7_2_ZIP("php-7.2-zip"),
    PHP_7_2_EXT_HTTP("php-7.2-ext-http"),
    PHP_7_2_EXT_IMAGICK("php-7.2-ext-imagick"),
    PHP_7_2_EXT_MEMCACHED("php-7.2-ext-memcached"),
    PHP_7_2_EXT_PROPRO("php-7.2-ext-propro"),
    PHP_7_2_EXT_RAPHF("php-7.2-ext-raphf"),
    PHP_7_3("php-7.3"),
    PHP_7_3_BCMATH("php-7.3-bcmath"),
    PHP_7_3_BZ2("php-7.3-bz2"),
    PHP_7_3_CURL("php-7.3-curl"),
    PHP_7_3_DBA("php-7.3-dba"),
    PHP_7_3_GD("php-7.3-gd"),
    PHP_7_3_GMP("php-7.3-gmp"),
    PHP_7_3_JSON("php-7.3-json"),
    PHP_7_3_MBSTRING("php-7.3-mbstring"),
    PHP_7_3_MYSQL("php-7.3-mysql"),
    PHP_7_3_PGSQL("php-7.3-pgsql"),
    PHP_7_3_PSPELL("php-7.3-pspell"),
    PHP_7_3_SOAP("php-7.3-soap"),
    PHP_7_3_SQLITE3("php-7.3-sqlite3"),
    PHP_7_3_XML("php-7.3-xml"),
    PHP_7_3_XMLRPC("php-7.3-xmlrpc"),
    PHP_7_3_ZIP("php-7.3-zip"),
    PHP_7_3_EXT_HTTP("php-7.3-ext-http"),
    PHP_7_3_EXT_IMAGICK("php-7.3-ext-imagick"),
    PHP_7_3_EXT_MEMCACHED("php-7.3-ext-memcached"),
    PHP_7_3_EXT_PROPRO("php-7.3-ext-propro"),
    PHP_7_3_EXT_RAPHF("php-7.3-ext-raphf"),
    PHP_7_4("php-7.4"),
    PHP_7_4_BCMATH("php-7.4-bcmath"),
    PHP_7_4_BZ2("php-7.4-bz2"),
    PHP_7_4_CURL("php-7.4-curl"),
    PHP_7_4_DBA("php-7.4-dba"),
    PHP_7_4_GD("php-7.4-gd"),
    PHP_7_4_GMP("php-7.4-gmp"),
    PHP_7_4_JSON("php-7.4-json"),
    PHP_7_4_MBSTRING("php-7.4-mbstring"),
    PHP_7_4_MYSQL("php-7.4-mysql"),
    PHP_7_4_PGSQL("php-7.4-pgsql"),
    PHP_7_4_PSPELL("php-7.4-pspell"),
    PHP_7_4_SOAP("php-7.4-soap"),
    PHP_7_4_SQLITE3("php-7.4-sqlite3"),
    PHP_7_4_XML("php-7.4-xml"),
    PHP_7_4_XMLRPC("php-7.4-xmlrpc"),
    PHP_7_4_ZIP("php-7.4-zip"),
    PHP_7_4_EXT_HTTP("php-7.4-ext-http"),
    PHP_7_4_EXT_IMAGICK("php-7.4-ext-imagick"),
    PHP_7_4_EXT_MEMCACHED("php-7.4-ext-memcached"),
    PHP_7_4_EXT_PROPRO("php-7.4-ext-propro"),
    PHP_7_4_EXT_RAPHF("php-7.4-ext-raphf"),
    PHP_8_0("php-8.0"),
    PHP_8_0_BCMATH("php-8.0-bcmath"),
    PHP_8_0_BZ2("php-8.0-bz2"),
    PHP_8_0_CURL("php-8.0-curl"),
    PHP_8_0_DBA("php-8.0-dba"),
    PHP_8_0_GD("php-8.0-gd"),
    PHP_8_0_GMP("php-8.0-gmp"),
    PHP_8_0_INTL("php-8.0-intl"),
    PHP_8_0_MBSTRING("php-8.0-mbstring"),
    PHP_8_0_MYSQL("php-8.0-mysql"),
    PHP_8_0_PGSQL("php-8.0-pgsql"),
    PHP_8_0_PSPELL("php-8.0-pspell"),
    PHP_8_0_SOAP("php-8.0-soap"),
    PHP_8_0_SQLITE3("php-8.0-sqlite3"),
    PHP_8_0_XML("php-8.0-xml"),
    PHP_8_0_ZIP("php-8.0-zip"),
    PHP_8_0_EXT_HTTP("php-8.0-ext-http"),
    PHP_8_0_EXT_IMAGICK("php-8.0-ext-imagick"),
    PHP_8_0_EXT_MEMCACHED("php-8.0-ext-memcached"),
    PHP_8_0_EXT_RAPHF("php-8.0-ext-raphf"),
    PHP_8_0_EXT_REDIS("php-8.0-ext-redis"),
    PHP_8_1("php-8.1"),
    PHP_8_1_BCMATH("php-8.1-bcmath"),
    PHP_8_1_BZ2("php-8.1-bz2"),
    PHP_8_1_CURL("php-8.1-curl"),
    PHP_8_1_DBA("php-8.1-dba"),
    PHP_8_1_GD("php-8.1-gd"),
    PHP_8_1_GMP("php-8.1-gmp"),
    PHP_8_1_INTL("php-8.1-intl"),
    PHP_8_1_MBSTRING("php-8.1-mbstring"),
    PHP_8_1_MYSQL("php-8.1-mysql"),
    PHP_8_1_PGSQL("php-8.1-pgsql"),
    PHP_8_1_PSPELL("php-8.1-pspell"),
    PHP_8_1_SOAP("php-8.1-soap"),
    PHP_8_1_SQLITE3("php-8.1-sqlite3"),
    PHP_8_1_XML("php-8.1-xml"),
    PHP_8_1_ZIP("php-8.1-zip"),
    PHP_8_1_EXT_HTTP("php-8.1-ext-http"),
    PHP_8_1_EXT_IMAGICK("php-8.1-ext-imagick"),
    PHP_8_1_EXT_MEMCACHED("php-8.1-ext-memcached"),
    PHP_8_1_EXT_RAPHF("php-8.1-ext-raphf"),
    PHP_8_1_EXT_REDIS("php-8.1-ext-redis"),
    PHP_8_2("php-8.2"),
    PHP_8_2_BCMATH("php-8.2-bcmath"),
    PHP_8_2_BZ2("php-8.2-bz2"),
    PHP_8_2_CURL("php-8.2-curl"),
    PHP_8_2_DBA("php-8.2-dba"),
    PHP_8_2_GD("php-8.2-gd"),
    PHP_8_2_GMP("php-8.2-gmp"),
    PHP_8_2_INTL("php-8.2-intl"),
    PHP_8_2_MBSTRING("php-8.2-mbstring"),
    PHP_8_2_MYSQL("php-8.2-mysql"),
    PHP_8_2_PGSQL("php-8.2-pgsql"),
    PHP_8_2_PSPELL("php-8.2-pspell"),
    PHP_8_2_SOAP("php-8.2-soap"),
    PHP_8_2_SQLITE3("php-8.2-sqlite3"),
    PHP_8_2_XML("php-8.2-xml"),
    PHP_8_2_ZIP("php-8.2-zip"),
    PHP_8_2_EXT_HTTP("php-8.2-ext-http"),
    PHP_8_2_EXT_IMAGICK("php-8.2-ext-imagick"),
    PHP_8_2_EXT_MEMCACHED("php-8.2-ext-memcached"),
    PHP_8_2_EXT_RAPHF("php-8.2-ext-raphf"),
    PHP_8_2_EXT_REDIS("php-8.2-ext-redis"),
    POLICYCOREUTILS("policycoreutils"),
    POLICYCOREUTILS_PYTHON("policycoreutils-python"),
    PROCMAIL("procmail"),
    PSMISC("psmisc"),
    SED("sed"),
    SENDMAIL("sendmail"),
    SENDMAIL_CF("sendmail-cf"),
    SENDMAIL_AFTER_NETWORK_ONLINE("sendmail-after-network-online"),
    SENDMAIL_COPY_CERTIFICATES("sendmail-copy-certificates"),
    SENDMAIL_N("sendmail-n"),
    // No longer needed since no more 3ware support: SMARTMONTOOLS("smartmontools"),
    SPAMASSASSIN("spamassassin"),
    SPAMASSASSIN_AFTER_NETWORK_ONLINE("spamassassin-after-network-online"),
    SSHD_AFTER_NETWORK_ONLINE("sshd-after-network-online"),
    SUDO("sudo"),
    SYSSTAT("sysstat"),
    TAR("tar"),
    TOMCAT_CONNECTORS("tomcat-connectors"),
    UTIL_LINUX("util-linux");

    private final String rpmName;

    // Only needed when trying to automatically remove packages: private final PackageName[] requires;
    private PackageName(String rpmName /* Only needed when trying to automatically remove packages: , PackageName... requires */) {
      this.rpmName = rpmName;
      // Only needed when trying to automatically remove packages: this.requires = requires;
    }

    @Override
    public String toString() {
      return rpmName;
    }

    public String getRpmName() {
      return rpmName;
    }
  }

  /**
   * The path to some executables.
   */
  private static final String
      RPM_EXE_PATH = "/bin/rpm", // TODO: /usr/bin/rpm once all CentOS 7
      YUM_EXE_PATH = "/usr/bin/yum";

  /**
   * The directory containing the RPM files.  Used to detect possible changes
   * to the installed RPMs.
   */
  private static final String VAR_LIB_RPM = "/var/lib/rpm";

  public static class Version implements Comparable<Version> {

    private static class Segment implements Comparable<Segment> {

      private static Segment[] parseSegments(String version) {
        final List<Segment> segments = new ArrayList<>();
        final StringBuilder buffer = new StringBuilder();
        boolean lastNumeric = false;
        final int len = version.length();
        for (int i = 0; i < len; i++) {
          final char ch = version.charAt(i);
          if (
              (ch >= '0' && ch <= '9')
                  || (ch >= 'a' && ch <= 'z')
                  || (ch >= 'A' && ch <= 'Z')
          ) {
            final boolean isNumeric = ch >= '0' && ch <= '9';
            if (buffer.length() > 0 && lastNumeric != isNumeric) {
              segments.add(new Segment(lastNumeric, buffer.toString()));
              buffer.setLength(0);
            }
            lastNumeric = isNumeric;
            buffer.append(ch);
          } else {
            if (buffer.length() > 0) {
              segments.add(new Segment(lastNumeric, buffer.toString()));
              buffer.setLength(0);
            }
          }
        }
        if (buffer.length() > 0) {
          segments.add(new Segment(lastNumeric, buffer.toString()));
        }
        return segments.toArray(new Segment[segments.size()]);
      }

      private final boolean numeric;
      private final String value;

      private Segment(boolean numeric, String value) {
        this.numeric = numeric;
        this.value = value;
      }

      @Override
      public String toString() {
        return value;
      }

      private boolean isNumeric() {
        return numeric;
      }

      private static String skipLeadingZeros(String value) {
        final int len = value.length();
        for (int i = 0; i < len; i++) {
          if (value.charAt(i) != '0') {
            return value.substring(i);
          }
        }
        return "";
      }

      @Override
      public int compareTo(Segment other) {
        if (isNumeric()) {
          if (other.isNumeric()) {
            // Note: as unbounded size integers...
            final String s1 = skipLeadingZeros(value);
            final String s2 = skipLeadingZeros(other.value);
            if (s1.length() > s2.length()) {
              return 1;
            }
            if (s1.length() < s2.length()) {
              return -1;
            }
            return s1.compareTo(s2);
          } else {
            // one segment is alpha and the other is numeric, the numeric one is "larger"
            return 1;
          }
        } else {
          if (other.isNumeric()) {
            // one segment is alpha and the other is numeric, the numeric one is "larger"
            return -1;
          } else {
            return value.compareTo(other.value);
          }
        }
      }
    }

    private final String version;
    private final Segment[] segments;

    public Version(String version) {
      this.version = version;
      this.segments = Segment.parseSegments(version);
    }

    @Override
    public String toString() {
      return version;
    }

    @Override
    public int hashCode() {
      return version.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof Version)) {
        return false;
      }
      Version other = (Version) obj;
      return version.equals(other.version);
    }

    /**
     * Compares two versions in segment order.
     * Implement same logic as RPM:
     * <a href="http://linux.derkeiler.com/Newsgroups/comp.os.linux.development.system/2005-12/msg00397.html">http://linux.derkeiler.com/Newsgroups/comp.os.linux.development.system/2005-12/msg00397.html</a>
     */
    @Override
    public int compareTo(Version other) {
      final int len = Math.max(segments.length, other.segments.length);
      for (int i = 0; i < len; i++) {
        if (i >= segments.length) {
          // the string with more segments is "newer".
          return 1;
        }
        if (i >= other.segments.length) {
          // the string with more segments is "newer".
          return -1;
        }
        int diff = segments[i].compareTo(other.segments[i]);
        if (diff != 0) {
          return diff;
        }
      }
      return 0;
    }

    /**
     * Parses the version then calls {@link #compareTo(com.aoindustries.aoserv.daemon.posix.linux.PackageManager.Version)}.
     */
    public int compareTo(String other) {
      return compareTo(new Version(other));
    }
  }

  public enum Architecture {
    noarch,
    i386,
    i486,
    i586,
    i686,
    x86_64
  }

  public static class Rpm implements Comparable<Rpm> {

    private final String name;
    private final Integer epoch;
    private final Version version;
    private final Version release;
    private final Architecture architecture;

    private Rpm(
        String name,
        Integer epoch,
        Version version,
        Version release,
        Architecture architecture
    ) {
      this.name = name;
      this.epoch = epoch;
      this.version = version;
      this.release = release;
      this.architecture = architecture;
    }

    @Override
    public String toString() {
      if (epoch == null) {
        return toStringWithoutEpoch();
      } else {
        if (architecture == null) {
          return name + '-' + epoch + ':' + version + '-' + release;
        } else {
          return name + '-' + epoch + ':' + version + '-' + release + '.' + architecture;
        }
      }
    }

    public String toStringWithoutEpoch() {
      if (architecture == null) {
        return name + '-' + version + '-' + release;
      } else {
        return name + '-' + version + '-' + release + '.' + architecture;
      }
    }

    @Override
    public int hashCode() {
      int hash = name.hashCode();
      hash = hash * 31 + Objects.hashCode(epoch);
      hash = hash * 31 + version.hashCode();
      hash = hash * 31 + release.hashCode();
      hash = hash * 31 + Objects.hashCode(architecture);
      return hash;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof Rpm)) {
        return false;
      }
      Rpm other = (Rpm) obj;
      return
          name.equals(other.name)
              && Objects.equals(epoch, other.epoch)
              && version.equals(other.version)
              && release.equals(other.release)
              && Objects.equals(architecture, other.architecture);
    }

    @Override
    public int compareTo(Rpm other) {
      int diff = name.compareTo(other.name);
      if (diff != 0) {
        return diff;
      }
      int e1 = epoch == null ? 0 : epoch;
      int e2 = other.epoch == null ? 0 : other.epoch;
      if (e1 < e2) {
        return -1;
      }
      if (e1 > e2) {
        return 1;
      }
      diff = version.compareTo(other.version);
      if (diff != 0) {
        return diff;
      }
      diff = release.compareTo(other.release);
      if (diff != 0) {
        return diff;
      }
      if (architecture != null) {
        if (other.architecture != null) {
          return architecture.compareTo(other.architecture);
        }
        return -1;
      } else {
        if (other.architecture != null) {
          return 1;
        }
        return 0;
      }
    }

    public String getName() {
      return name;
    }

    public Version getVersion() {
      return version;
    }

    public Version getRelease() {
      return release;
    }

    public Architecture getArchitecture() {
      return architecture;
    }

    /**
     * Removes this package.
     */
    public void remove() throws IOException {
      if (!AoservDaemonConfiguration.isPackageManagerUninstallEnabled()) {
        throw new IllegalStateException("Package uninstall is disabled in aoserv-daemon.properties");
      }
      String packageIdentifier = this.toStringWithoutEpoch();
      if (logger.isLoggable(Level.INFO)) {
        logger.info("Removing package: " + packageIdentifier);
      }
      synchronized (packagesLock) {
        AoservDaemon.exec(
            RPM_EXE_PATH,
            "-e",
            packageIdentifier
        );
      }
    }
  }

  private static final Object packagesLock = new Object();
  private static DirectoryMetaSnapshot lastSnapshot;
  private static SortedSet<Rpm> lastAllRpms;

  /**
   * Gets the set of all packages installed on the server.  Because
   * <code>rpm -aq</code> can take several seconds to run, the results are
   * cached and only updated when a file in <code>/var/lib/rpm</code> has
   * been modified.
   */
  public static SortedSet<Rpm> getAllRpms() throws IOException {
    synchronized (packagesLock) {
      DirectoryMetaSnapshot currentDirectorySnapshot = new DirectoryMetaSnapshot(VAR_LIB_RPM);
      if (logger.isLoggable(Level.FINER)) {
        StringBuilder message = new StringBuilder();
        message.append("Got directory snapshot:");
        boolean didOne = false;
        for (Map.Entry<String, DirectoryMetaSnapshot.FileMetaSnapshot> entry : currentDirectorySnapshot.getFiles().entrySet()) {
          if (didOne) {
            message.append('\n');
          } else {
            didOne = true;
          }
          message.append("    ");
          message.append(entry.getKey());
          DirectoryMetaSnapshot.FileMetaSnapshot meta = entry.getValue();
          message.append(' ');
          message.append(meta.getLastModified());
          message.append(' ');
          message.append(meta.getLength());
        }
        logger.finer(message.toString());
      }
      if (!currentDirectorySnapshot.equals(lastSnapshot)) {
        // Get all RPMs
        SortedSet<Rpm> newAllRpms = new TreeSet<>();
        List<String> lines = Strings.splitLines(
            AoservDaemon.execAndCapture(
                RPM_EXE_PATH,
                "-q",
                "-a",
                "--queryformat",
                "%{NAME}\\n%{EPOCH}\\n%{VERSION}\\n%{RELEASE}\\n%{ARCH}\\n"
            )
        );
        if ((lines.size() % 5) != 0) {
          throw new AssertionError("lines.size() not a multiple of 5: " + lines.size());
        }
        for (int i = 0; i < lines.size(); i += 5) {
          final String epoch = lines.get(i + 1);
          final String arch = lines.get(i + 4);
          newAllRpms.add(
              new Rpm(
                  lines.get(i),
                  "(none)".equals(epoch) ? null : Integer.parseInt(epoch),
                  new Version(lines.get(i + 2)),
                  new Version(lines.get(i + 3)),
                  "(none)".equals(arch) ? null : Architecture.valueOf(arch)
              )
          );
        }
        // Get snapshot after RPM transaction completes because querying the RPMs updates timestamps.
        currentDirectorySnapshot = new DirectoryMetaSnapshot(VAR_LIB_RPM);
        if (logger.isLoggable(Level.FINER)) {
          StringBuilder message = new StringBuilder();
          message.append("Got directory snapshot after rpm -q:");
          boolean didOne = false;
          for (Map.Entry<String, DirectoryMetaSnapshot.FileMetaSnapshot> entry : currentDirectorySnapshot.getFiles().entrySet()) {
            if (didOne) {
              message.append('\n');
            } else {
              didOne = true;
            }
            message.append("    ");
            message.append(entry.getKey());
            DirectoryMetaSnapshot.FileMetaSnapshot meta = entry.getValue();
            message.append(' ');
            message.append(meta.getLastModified());
            message.append(' ');
            message.append(meta.getLength());
          }
          logger.finer(message.toString());
        }
        lastSnapshot = currentDirectorySnapshot;
        // When list hasn't changed, use old list and do not call listeners
        if (!newAllRpms.equals(lastAllRpms)) {
          SortedSet<Rpm> unmodifiableAllRpms = Collections.unmodifiableSortedSet(newAllRpms);
          lastAllRpms = unmodifiableAllRpms;
          if (logger.isLoggable(Level.FINE)) {
            StringBuilder message = new StringBuilder();
            message.append("Got all RPMs:");
            for (Rpm rpm : lastAllRpms) {
              message.append("\n    ");
              message.append(rpm);
            }
            logger.fine(message.toString());
          }
          // Notify any listeners
          listenerManager.enqueueEvent(
              listener -> () -> listener.packageListUpdated(unmodifiableAllRpms)
          );
        } else {
          logger.fine("RPMs not changed");
        }
      }
      return lastAllRpms;
    }
  }

  /**
   * Gets the highest version of an installed package or <code>null</code> if
   * not installed.
   */
  public static Rpm getInstalledPackage(PackageName name) throws IOException {
    // Looking through all to find highest version
    Rpm highestVersionFound = null;
    for (Rpm rpm : getAllRpms()) {
      if (rpm.getName().equals(name.rpmName)) {
        highestVersionFound = rpm;
      }
    }
    if (logger.isLoggable(Level.FINER)) {
      if (highestVersionFound == null) {
        logger.finer("No installed package found for " + name);
      } else {
        logger.finer("Highest installed package for " + name + ": " + highestVersionFound);
      }
    }
    return highestVersionFound;
  }

  /**
   * Installs a package if it is not currently installed.
   * If the package is already installed, no action is taken.
   * The package is installed with "yum -q -y install $NAME".
   * If multiple packages are already installed, the highest version is returned.
   *
   * @return  the highest version of RPM that is installed
   */
  public static Rpm installPackage(PackageName name) throws IOException {
    return installPackage(name, null);
  }

  /**
   * Installs a package if it is not currently installed.
   * If the package is already installed, no action is taken.
   * The package is installed with "yum -q -y install $NAME".
   * If multiple packages are already installed, the highest version is returned.
   *
   * @param onInstall  Called when the RPM is actually installed.  Not called if already installed.
   *
   * @return  the highest version of RPM that is installed
   */
  public static Rpm installPackage(PackageName name, IORunnable onInstall) throws IOException {
    synchronized (packagesLock) {
      // Check if exists by looking through all to find highest version
      Rpm highestVersionFound = getInstalledPackage(name);
      if (highestVersionFound != null) {
        return highestVersionFound;
      }
      // Install with yum
      if (logger.isLoggable(Level.INFO)) {
        logger.info("Installing package: " + name.rpmName);
      }
      AoservDaemon.exec(
          YUM_EXE_PATH,
          "-q",
          "-y",
          "install",
          name.rpmName
      );
      if (onInstall != null) {
        onInstall.run();
      }
      // Must exist now
      for (Rpm rpm : getAllRpms()) {
        if (rpm.getName().equals(name.rpmName)) {
          return rpm;
        }
      }
      throw new AssertionError("Package does not exist after yum install: " + name.rpmName);
    }
  }

  /**
   * Installs all of the packages that are not currently installed.
   * If a package is already installed, no action is taken for that package.
   *
   * @see  #installPackage(com.aoindustries.aoserv.daemon.unix.linux.PackageManager.PackageName)
   */
  public static void installPackages(Iterable<PackageName> packageNames) throws IOException {
    synchronized (packagesLock) {
      for (PackageName packageName : packageNames) {
        installPackage(packageName);
      }
    }
  }

  /**
   * Installs all of the packages that are not currently installed.
   * If a package is already installed, no action is taken for that package.
   *
   * @see  #installPackage(com.aoindustries.aoserv.daemon.unix.linux.PackageManager.PackageName)
   */
  public static void installPackages(PackageName ... packageNames) throws IOException {
    synchronized (packagesLock) {
      for (PackageName packageName : packageNames) {
        installPackage(packageName);
      }
    }
  }

  /**
   * Removes the package with the provided name.  There must be only one
   * match for the provided name.  If the RPM is not installed, no action is
   * taken.  If more than one version is installed, an exception is thrown.
   *
   * @return  {@code true} when a package was removed
   *
   * @see Rpm#remove() Call remove on a specific RPM when multiple version may be installed
   */
  public static boolean removePackage(PackageName name) throws IOException {
    if (!AoservDaemonConfiguration.isPackageManagerUninstallEnabled()) {
      throw new IllegalStateException("Package uninstall is disabled in aoserv-daemon.properties");
    }
    synchronized (packagesLock) {
      List<Rpm> matches = new ArrayList<>();
      for (Rpm rpm : getAllRpms()) {
        if (rpm.getName().equals(name.rpmName)) {
          matches.add(rpm);
        }
      }
      if (!matches.isEmpty()) {
        if (matches.size() > 1) {
          throw new IOException("More than one installed RPM matches, refusing to remove: " + name);
        }
        matches.get(0).remove();
        return true;
      } else {
        return false;
      }
    }
  }

  /**
   * Called when the package list is updated or first loaded.
   */
  public static interface PackageListener {

    /**
     * Called when the package list is updated or first loaded.
     */
    void packageListUpdated(SortedSet<Rpm> allRpms);
  }

  /**
   * TODO: Instead of polling for updates, use file watches to be notified when something changes.
   *       We have some recursive file watch inside the SemanticCMS AutoGit projects.  It should probably
   *       be pulled-out into a shared utility to simplify the watching of directory trees.
   *       Unless we can find a well packaged, documented, and supported package that does this.
   *       If not, ours should become it.
   */
  private static final Object pollThreadLock = new Object();
  private static Thread pollThread;

  private static final ConcurrentListenerManager<PackageListener> listenerManager = new ConcurrentListenerManager<>();

  /**
   * Adds a new package listener to be notified when the package list is updated or first loaded.
   *
   * @see  #removePackageListener(com.aoindustries.aoserv.daemon.unix.linux.PackageManager.PackageListener)
   * @see  ConcurrentListenerManager#addListener(java.lang.Object, boolean)
   */
  public static void addPackageListener(PackageListener listener) {
    listenerManager.addListener(listener, false);
    synchronized (pollThreadLock) {
      if (pollThread == null) {
        pollThread = new Thread(
            () -> {
              while (true) {
                try {
                  try {
                    Thread.sleep(60000); // Sleep one minute between polls
                  } catch (InterruptedException e) {
                    logger.log(Level.WARNING, null, e);
                    // Restore the interrupted status
                    Thread.currentThread().interrupt();
                    break;
                  }
                  getAllRpms();
                } catch (ThreadDeath td) {
                  throw td;
                } catch (Throwable t) {
                  logger.log(Level.SEVERE, null, t);
                }
              }
            },
            "pollThread"
        );
        pollThread.setPriority(Thread.NORM_PRIORITY - 2);
        pollThread.start();
      }
    }
  }

  /**
   * Removes a package listener.
   *
   * @return  {@code true} if the listener was removed, {@code false} when not found
   *
   * @see  #addPackageListener(com.aoindustries.aoserv.daemon.unix.linux.PackageManager.PackageListener)
   * @see  ConcurrentListenerManager#removeListener(java.lang.Object)
   */
  public static boolean removePackageListener(PackageListener listener) {
    return listenerManager.removeListener(listener);
  }
}
