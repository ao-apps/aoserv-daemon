package com.aoindustries.aoserv.daemon;

/*
 * Copyright 2001-2010 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.validator.DomainName;
import com.aoindustries.aoserv.client.validator.Email;
import com.aoindustries.aoserv.client.validator.Hostname;
import com.aoindustries.aoserv.client.validator.MySQLUserId;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.client.validator.ValidationException;
import com.aoindustries.io.AOPool;
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

    private AOServDaemonConfiguration() {
    }

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
    
    public static boolean isNested() throws IOException {
        return "true".equalsIgnoreCase(getProperty("nested"));
    }

    public static Email getMonitorEmailFullTo() throws IOException {
        try {
            return Email.valueOf(getProperty("monitor.email.full.to")).intern();
        } catch(ValidationException err) {
            throw new IOException(err.getMessage());
        }
    }

    public static Email getMonitorEmailFullFrom() throws IOException {
        try {
            return Email.valueOf(getProperty("monitor.email.full.from")).intern();
        } catch(ValidationException err) {
            throw new IOException(err.getMessage());
        }
    }

    public static Email getMonitorEmailSummaryTo() throws IOException {
        try {
            return Email.valueOf(getProperty("monitor.email.summary.to")).intern();
        } catch(ValidationException err) {
            throw new IOException(err.getMessage());
        }
    }

    public static Email getMonitorEmailSummaryFrom() throws IOException {
        try {
            return Email.valueOf(getProperty("monitor.email.summary.from")).intern();
        } catch(ValidationException err) {
            throw new IOException(err.getMessage());
        }
    }

    public static Hostname getMonitorSmtpServer() throws IOException {
        try {
            return Hostname.valueOf(getProperty("monitor.smtp.server")).intern();
        } catch(ValidationException err) {
            throw new IOException(err.getMessage());
        }
    }

    public static DomainName getServerHostname() throws IOException {
        try {
            return DomainName.valueOf(getProperty("server.hostname")).intern();
        } catch(ValidationException err) {
            throw new IOException(err.getMessage());
        }
    }

    public static String getSSLKeystorePassword() throws IOException {
        String s = getProperty("ssl.keystore.password");
        return s==null || s.length()==0 ? null : s;
    }

    public static UnixPath getSSLKeystorePath() throws IOException {
        try {
            String s = getProperty("ssl.keystore.path");
            return s==null || s.length()==0 ? null : UnixPath.valueOf(s);
        } catch(ValidationException err) {
            throw new IOException(err.getMessage());
        }
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

    public static String getMysqlDriver() throws IOException {
        return getProperty("mysql.driver");
    }

    public static MySQLUserId getMysqlUser() throws IOException {
        try {
            String s = getProperty("mysql.user");
            return s==null || s.length()==0 ? null : MySQLUserId.valueOf(s);
        } catch(ValidationException err) {
            throw new IOException(err.getMessage());
        }
    }

    public static String getMysqlPassword() throws IOException {
        return getProperty("mysql.password");
    }

    public static int getMySqlConnections() throws IOException {
        return Integer.parseInt(getProperty("mysql.connections"));
    }

    public static long getMySqlMaxConnectionAge() throws IOException {
        String S=getProperty("mysql.max_connection_age");
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
