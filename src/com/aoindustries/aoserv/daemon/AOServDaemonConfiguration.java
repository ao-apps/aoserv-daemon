package com.aoindustries.aoserv.daemon;

/*
 * Copyright 2001-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.io.AOPool;
import com.aoindustries.profiler.Profiler;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The configuration for all AOServ processes is stored in a properties file.
 *
 * @author  AO Industries, Inc.
 */
final public class AOServDaemonConfiguration {

    private static Properties props;

    private static String getProperty(String name) throws IOException {
        synchronized(AOServDaemonConfiguration.class) {
            Properties newProps = new Properties();
            if (props == null) {
                InputStream in = new BufferedInputStream(AOServDaemonConfiguration.class.getResourceAsStream("aoserv-daemon.properties"));
                try {
                    newProps.load(in);
                } finally {
                    in.close();
                }
                props = newProps;
            }
            return props.getProperty("aoserv.daemon."+name);
        }
    }
    
    public static int getProfilerLevel() throws IOException {
        return Profiler.parseProfilerLevel(getProperty("profiler.level"));
    }

    public static boolean isNested() throws IOException {
        return "true".equalsIgnoreCase(getProperty("nested"));
    }

    public static String getErrorEmailTo() throws IOException {
        return getProperty("error.email.to");
    }

    public static String getErrorEmailFrom() throws IOException {
        return getProperty("error.email.from");
    }

    public static String getErrorSmtpServer() throws IOException {
        return getProperty("error.smtp.server");
    }

    public static String getWarningEmailTo() throws IOException {
        return getProperty("warning.email.to");
    }

    public static String getWarningEmailFrom() throws IOException {
        return getProperty("warning.email.from");
    }

    public static String getWarningSmtpServer() throws IOException {
        return getProperty("warning.smtp.server");
    }

    public static String getSecurityEmailTo() throws IOException {
        return getProperty("security.email.to");
    }

    public static String getSecurityEmailFrom() throws IOException {
        return getProperty("security.email.from");
    }

    public static String getSecuritySmtpServer() throws IOException {
        return getProperty("security.smtp.server");
    }

    public static String getMonitorEmailFullTo() throws IOException {
        return getProperty("monitor.email.full.to");
    }

    public static String getMonitorEmailFullFrom() throws IOException {
        return getProperty("monitor.email.full.from");
    }

    public static String getMonitorEmailSummaryTo() throws IOException {
        return getProperty("monitor.email.summary.to");
    }

    public static String getMonitorEmailSummaryFrom() throws IOException {
        return getProperty("monitor.email.summary.from");
    }

    public static String getMonitorSmtpServer() throws IOException {
        return getProperty("monitor.smtp.server");
    }

    public static String getServerHostname() throws IOException {
        return getProperty("server.hostname");
    }

    public static String getSSLKeystorePassword() throws IOException {
        return getProperty("ssl.keystore.password");
    }

    public static String getSSLKeystorePath() throws IOException {
        return getProperty("ssl.keystore.path");
    }

    /**
     * Gets the maximum backup bandwidth in bits per second or <code>-1</code> if unlimited.
     */
    public static int getBackupBandwidthLimit() throws IOException {
        String S=getProperty("backup.bandwidth.limit");
        return S==null || S.length()==0 ? -1 : Integer.parseInt(S);
    }

    public static int getBackupReplicatorConcurrency() throws IOException {
        return Integer.parseInt(getProperty("backup.replicator.concurrency"));
    }

    public static String getBackupReplicatorPath() throws IOException {
        return getProperty("backup.replicator.path");
    }

    public static String getPostgresPassword() throws IOException {
        return getProperty("postgres.password");
    }

    public static int getPostgresConnections() throws IOException {
        return Integer.parseInt(getProperty("postgres.connections"));
    }

    public static long getPostgresMaxConnectionAge() throws IOException {
        String S=getProperty("postgres.max_connection_age");
        return S==null || S.length()==0 ? AOPool.DEFAULT_MAX_CONNECTION_AGE : Long.parseLong(S);
    }

    public static String getMySqlDriver() throws IOException {
        return getProperty("mysql.driver");
    }

    public static String getMySqlUser() throws IOException {
        return getProperty("mysql.user");
    }

    public static String getMySqlPassword() throws IOException {
        return getProperty("mysql.password");
    }

    public static int getMySqlConnections() throws IOException {
        return Integer.parseInt(getProperty("mysql.connections"));
    }

    public static long getMySqlMaxConnectionAge() throws IOException {
        String S=getProperty("mysql.max_connection_age");
        return S==null || S.length()==0 ? AOPool.DEFAULT_MAX_CONNECTION_AGE : Long.parseLong(S);
    }

    public static String getInterBasePassword() throws IOException {
        return getProperty("interbase.password");
    }

    public static int getInterBaseConnections() throws IOException {
        return Integer.parseInt(getProperty("interbase.connections"));
    }

    public static long getInterBaseMaxConnectionAge() throws IOException {
        String S=getProperty("interbase.max_connection_age");
        return S==null || S.length()==0 ? AOPool.DEFAULT_MAX_CONNECTION_AGE : Long.parseLong(S);
    }

    public static String getCyrusPassword() throws IOException {
        return getProperty("cyrus.password");
    }

    public static boolean isManagerEnabled(Class clazz) throws IOException {
        final String stripPrefix="com.aoindustries.aoserv.daemon.";
        String key=clazz.getName();
        if(key.startsWith(stripPrefix)) key=key.substring(stripPrefix.length());
        key=key+".enabled";
        String value=getProperty(key);
        if("true".equalsIgnoreCase(value)) return true;
        if("false".equalsIgnoreCase(value)) return false;
        throw new IOException("Value in aoserv-daemon.properties must be either \"true\" or \"false\": "+key);
    }
}
