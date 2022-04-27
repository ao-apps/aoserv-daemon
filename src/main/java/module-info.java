/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2021, 2022  AO Industries, Inc.
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
module com.aoindustries.aoserv.daemon {
  exports com.aoindustries.aoserv.daemon;
  exports com.aoindustries.aoserv.daemon.backup;
  exports com.aoindustries.aoserv.daemon.cvsd;
  exports com.aoindustries.aoserv.daemon.distro;
  exports com.aoindustries.aoserv.daemon.dns;
  exports com.aoindustries.aoserv.daemon.email;
  exports com.aoindustries.aoserv.daemon.email.jilter;
  exports com.aoindustries.aoserv.daemon.failover;
  exports com.aoindustries.aoserv.daemon.ftp;
  exports com.aoindustries.aoserv.daemon.httpd;
  exports com.aoindustries.aoserv.daemon.httpd.jboss;
  exports com.aoindustries.aoserv.daemon.httpd.tomcat;
  exports com.aoindustries.aoserv.daemon.iptables;
  exports com.aoindustries.aoserv.daemon.monitor;
  exports com.aoindustries.aoserv.daemon.mysql;
  exports com.aoindustries.aoserv.daemon.net;
  exports com.aoindustries.aoserv.daemon.net.fail2ban;
  exports com.aoindustries.aoserv.daemon.net.firewalld;
  exports com.aoindustries.aoserv.daemon.net.ssh;
  exports com.aoindustries.aoserv.daemon.net.xinetd;
  exports com.aoindustries.aoserv.daemon.posix;
  exports com.aoindustries.aoserv.daemon.posix.linux;
  exports com.aoindustries.aoserv.daemon.postgres;
  exports com.aoindustries.aoserv.daemon.random;
  exports com.aoindustries.aoserv.daemon.report;
  exports com.aoindustries.aoserv.daemon.server;
  exports com.aoindustries.aoserv.daemon.ssl;
  exports com.aoindustries.aoserv.daemon.timezone;
  exports com.aoindustries.aoserv.daemon.util;
  // Direct
  requires com.aoapps.collections; // <groupId>com.aoapps</groupId><artifactId>ao-collections</artifactId>
  requires com.aoapps.concurrent; // <groupId>com.aoapps</groupId><artifactId>ao-concurrent</artifactId>
  requires com.aoapps.cron; // <groupId>com.aoapps</groupId><artifactId>ao-cron</artifactId>
  requires com.aoapps.encoding; // <groupId>com.aoapps</groupId><artifactId>ao-encoding</artifactId>
  requires com.aoindustries.firewalld; // <groupId>com.aoindustries</groupId><artifactId>ao-firewalld</artifactId>
  requires com.aoapps.hodgepodge; // <groupId>com.aoapps</groupId><artifactId>ao-hodgepodge</artifactId>
  requires com.aoapps.io.filesystems; // <groupId>com.aoapps</groupId><artifactId>ao-io-filesystems</artifactId>
  requires com.aoapps.io.filesystems.posix; // <groupId>com.aoapps</groupId><artifactId>ao-io-filesystems-posix</artifactId>
  requires com.aoapps.io.posix; // <groupId>com.aoapps</groupId><artifactId>ao-io-posix</artifactId>
  requires com.aoapps.lang; // <groupId>com.aoapps</groupId><artifactId>ao-lang</artifactId>
  requires com.aoapps.net.types; // <groupId>com.aoapps</groupId><artifactId>ao-net-types</artifactId>
  requires com.aoapps.security; // <groupId>com.aoapps</groupId><artifactId>ao-security</artifactId>
  requires com.aoindustries.selinux; // <groupId>com.aoindustries</groupId><artifactId>ao-selinux</artifactId>
  requires com.aoapps.sql; // <groupId>com.aoapps</groupId><artifactId>ao-sql</artifactId>
  requires com.aoapps.sql.pool; // <groupId>com.aoapps</groupId><artifactId>ao-sql-pool</artifactId>
  requires com.aoapps.tempfiles; // <groupId>com.aoapps</groupId><artifactId>ao-tempfiles</artifactId>
  requires com.aoindustries.aoserv.backup; // <groupId>com.aoindustries</groupId><artifactId>aoserv-backup</artifactId>
  requires com.aoindustries.aoserv.client; // <groupId>com.aoindustries</groupId><artifactId>aoserv-client</artifactId>
  requires com.aoindustries.aoserv.daemon.client; // <groupId>com.aoindustries</groupId><artifactId>aoserv-daemon-client</artifactId>
  requires com.aoindustries.aoserv.jilter.config; // <groupId>com.aoindustries</groupId><artifactId>aoserv-jilter-config</artifactId>
  requires org.apache.commons.lang3; // <groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId>
  requires com.google.gson; // <groupId>com.google.code.gson</groupId><artifactId>gson</artifactId>
  requires java.mail; // <groupId>com.sun.mail</groupId><artifactId>javax.mail</artifactId>
  requires com.aoindustries.noc.monitor.portmon; // <groupId>com.aoindustries</groupId><artifactId>noc-monitor-portmon</artifactId>
  // Java SE
  //requires java.logging;
  requires java.naming;
  requires java.sql;
} // TODO: Avoiding rewrite-maven-plugin-4.22.2 truncation
