package com.aoindustries.aoserv.daemon.httpd;

/*
 * Copyright 2000-2006 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.aoserv.daemon.backup.*;
import com.aoindustries.aoserv.daemon.ftp.*;
import com.aoindustries.aoserv.daemon.unix.linux.*;
import com.aoindustries.aoserv.daemon.util.*;
import com.aoindustries.io.*;
import com.aoindustries.io.unix.*;
import com.aoindustries.profiler.*;
import com.aoindustries.sql.*;
import com.aoindustries.util.*;
import com.aoindustries.util.sort.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * The configuration for all AOServ processes is stored in a properties file.
 *
 * @author  AO Industries, Inc.
 */
final public class HttpdManager extends BuilderThread {

    /**
     * The default PHP version.
     */
    //public static final String DEFAULT_REDHAT72_PHP_VERSION="4.3.3";
    //public static final String DEFAULT_MANDRAKE92_PHP_VERSION="4";
    public static final String DEFAULT_MANDRAKE_10_1_PHP_VERSION="4";

    /**
     * The version of PostgreSQL minor version used by the default PHP version.
     */
    public static final String PHP_POSTGRES_MINOR_VERSION="8.1";

    /**
     * The default JDK version.
     */
    //public static final String DEFAULT_REDHAT_7_2_JDK_VERSION="j2sdk1.4.2_04";
    //public static final String DEFAULT_MANDRAKE_9_2_JDK_VERSION="j2sdk1.4.2_08";
    public static final String DEFAULT_MANDRAKE_10_1_JDK_VERSION="j2sdk1.4.2_08";
    public static final String DEFAULT_TOMCAT_5_JDK_VERSION="jdk1.5.0";

    /**
     * The default JDK paths.
     */
    //public static final String DEFAULT_REDHAT_7_2_JDK_PATH="/usr/j2sdk1.4.2_04";
    //public static final String DEFAULT_MANDRAKE_9_2_JDK_PATH="/usr/j2sdk/1.4.2_08";
    public static final String DEFAULT_MANDRAKE_10_1_JDK_PATH="/usr/j2sdk/1.4.2_08";
    public static final String DEFAULT_TOMCAT_5_JDK_PATH="/usr/jdk/1.5.0";

    /**
     * The directory that HTTPD configs are located in.
     */
    public static final String CONFIG_DIRECTORY="/etc/httpd";

    /**
     * The directory that individual host and bind configurations are in.
     */
    public static final String CONF_HOSTS=CONFIG_DIRECTORY+"/conf/hosts";

    /**
     * The directory containing wwwgroup config files
     */
    public static final String CONF_WWWGROUPS=CONFIG_DIRECTORY+"/conf/wwwgroup";
	
    /**
     * The worker files.
     */
    public static final String
        WORKERS_BEGINNING=CONFIG_DIRECTORY+"/conf/workers",
        WORKERS_ENDING=".properties",
        WORKERS_ENDING_NEW=WORKERS_ENDING+".new",
        WORKERS_ENDING_OLD=WORKERS_ENDING+".old",

        HTTPD_CONF_BEGINNING=CONFIG_DIRECTORY+"/conf/httpd",
        HTTPD_CONF_ENDING=".conf",
        HTTPD_CONF_ENDING_NEW=HTTPD_CONF_ENDING+".new",
        HTTPD_CONF_ENDING_OLD=HTTPD_CONF_ENDING+".old"
    ;

    /**
     * The directory that logs are stored in.
     */
    public static final String LOG_DIR="/logs";

    /**
     * The directory that contains the log rotation scripts.
     */
    public static final String LOG_ROTATION_DIR="/etc/httpd/conf/logrotate.d";

    /**
     * The characters that may be used inside a crypt salt.
     */
    private static char[] cryptChars={
        '.', '/', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D',
        'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T',
        'U', 'V', 'W', 'X', 'Y', 'Z', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j',
        'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z'
    };

    /**
     * The list of files that are contained in /www/{site}/var/log directories.
     */
    private static final String[] tomcatLogFiles={
        "jasper.log",
        "jvm_crashes.log",
        "servlet.log",
        "servlet_err",
        "tomcat.log"
    };

    private static final String[] standardProtectedURLs={
        ".*/CVS/Entries$",
        ".*/CVS/Entries~$",
        ".*/CVS/Entries.Repository$",
        ".*/CVS/Entries.Repository~$",
        ".*/CVS/Entries.Root$",
        ".*/CVS/Entries.Root~$",
        ".*/CVS/Entries.Static$",
        ".*/CVS/Entries.Static~$"
    };

    /**
     * Keeps track of the last start times for each Java VM.
     */
    private static final Map<String,Long> startJVMTimes=new HashMap<String,Long>();

    private static HttpdManager httpdManager;

    private HttpdManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, HttpdManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    private static void buildStaticSite(HttpdStaticSite staticSite, LinuxServerAccount lsa, LinuxServerGroup lsg) throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, HttpdManager.class, "buildStaticSite(HttpdStaticSite,LinuxServerAccount,LinuxServerGroup)", null);
        try {
            throw new SQLException("Static site creation not yet supported.");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static boolean buildTomcatSite(HttpdTomcatSite tomcatSite, LinuxServerAccount lsa, LinuxServerGroup lsg) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "buildTomcatSite(HttpdTomcatSite,LinuxServerAccount,LinuxServerGroup)", null);
        try {
            AOServConnector connector=AOServDaemon.getConnector();
            AOServer thisAOServer=tomcatSite.getHttpdSite().getAOServer();
            if(!thisAOServer.equals(AOServDaemon.getThisAOServer())) throw new SQLException("ao_server mismatch");
            OperatingSystemVersion OSV=thisAOServer.getServer().getOperatingSystemVersion();
            int osv=OSV.getPKey();
            HttpdTomcatStdSite stdSite=tomcatSite.getHttpdTomcatStdSite();
            HttpdTomcatSharedSite shrSite = tomcatSite.getHttpdTomcatSharedSite();
            HttpdJBossSite jbossSite = tomcatSite.getHttpdJBossSite();
            int uid=lsa.getUID().getID();
            int gid=lsg.getGID().getID();
            LinuxServerAccount rootLSA=connector
                .usernames
                .get(LinuxAccount.ROOT)
                .getLinuxAccount()
                .getLinuxServerAccount(AOServDaemon.getThisAOServer())
            ;
            boolean isStandard=stdSite!=null;
            boolean isShared=shrSite!=null;
            boolean isJBoss=jbossSite!=null;
            if(isStandard || isShared || isJBoss) {
                /*
                 * Resolve and allocate stuff used throughout the method
                 */
                final HttpdSite site=tomcatSite.getHttpdSite();
                final String siteName=site.getSiteName();
                final String siteDir=HttpdSite.WWW_DIRECTORY+'/'+siteName;
                final HttpdTomcatVersion htv=tomcatSite.getHttpdTomcatVersion();
                final String tomcatDirectory=htv.getInstallDirectory();
                final String laUsername=lsa.getLinuxAccount().getUsername().getUsername();
                final String laGroupname = lsg.getLinuxGroup().getName();
                final String primaryUrl=site.getPrimaryHttpdSiteURL().getHostname();
                String tomcatVersion=htv.getTechnologyVersion(connector).getVersion();
                final boolean isTomcat4=htv.isTomcat4(connector);
                final boolean isTomcat55=htv.isTomcat55(connector);
                PostgresServer postgresServer=thisAOServer.getPreferredPostgresServer();
                String postgresServerMinorVersion=postgresServer==null?null:postgresServer.getPostgresVersion().getMinorVersion();

                /*
                 * Create the skeleton of the site, the directories and links.
                 */
                mkdir(siteDir+"/bin", 0770, lsa, lsg);
                if(!isTomcat4) ln("webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/cgi-bin", siteDir+"/cgi-bin", uid, gid);
                mkdir(
                    siteDir+"/conf",
                    !isTomcat4 && isShared && shrSite.getHttpdSharedTomcat().isSecure()?01775:0775,
                    !isTomcat4 && isShared && shrSite.getHttpdSharedTomcat().isSecure()?rootLSA:lsa,
                    lsg
                );
                mkdir(siteDir+"/daemon", 0770, lsa, lsg);
                if (site.getDisableLog()==null && isStandard) ln("../bin/tomcat", siteDir+"/daemon/tomcat", uid, gid);
                if(!isTomcat4) ln("webapps/"+HttpdTomcatContext.ROOT_DOC_BASE, siteDir+"/htdocs", uid, gid);
                if(!isTomcat4) mkdir(siteDir+"/lib", 0770, lsa, lsg);
                if(isTomcat4 && isStandard) mkdir(siteDir+"/temp", 0770, lsa, lsg);
                if(isTomcat4) ln("var/log", siteDir+"/logs", uid, gid);
                if(!isTomcat4) ln("webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/classes", siteDir+"/servlet", uid, gid);
                if(isShared && shrSite.getHttpdSharedTomcat().isSecure()) {
                    HttpdSharedTomcat hst=shrSite.getHttpdSharedTomcat();
                    LinuxServerAccount shrLSA=hst.getLinuxServerAccount();
                    LinuxServerGroup shrLSG=hst.getLinuxServerGroup();
                    mkdir(siteDir+"/var", 01750, shrLSA, shrLSG);
                    mkdir(siteDir+"/var/log", 01750, shrLSA, shrLSG);
                } else {
                    mkdir(siteDir+"/var", 0770, lsa, lsg);
                    mkdir(siteDir+"/var/log", 0770, lsa, lsg);
                }
                if(isStandard || isJBoss) mkdir(siteDir+"/var/run", 0770, lsa, lsg);
                mkdir(siteDir+"/webapps", 0775, lsa, lsg);
                if(!isTomcat4) ln(tomcatDirectory+"/webapps/examples", siteDir+"/webapps/examples", uid, gid);
                if (isJBoss) {
                    String templateDir = jbossSite.getHttpdJBossVersion().getTemplateDirectory();
                    File f = new File(templateDir);
                    String[] contents = f.list();
                    String[] command = new String[contents.length+3];
                    command[0] = "/bin/cp";
                    command[1] = "-rdp";
                    command[command.length-1] = siteDir;
                    for (int i = 0; i < contents.length; i++) command[i+2] = templateDir+"/"+contents[i];
                    AOServDaemon.exec(command);
                    String[] command2 = {
                        "/bin/chown", 
                        "-R", 
                        laUsername+"."+laGroupname,
                        siteDir+"/jboss",
                        siteDir+"/bin",
                        siteDir+"/lib",
                        siteDir+"/daemon"
                    };
                    AOServDaemon.exec(command2);

                    String jbossConfDir = siteDir+"/jboss/conf/tomcat";
                    File f2 = new File(jbossConfDir);
                    String[] f2contents = f2.list();

                    String[] command3 = new String[5];
                    command3[0] = "/usr/bin/replace";
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
                    // here!!!!!    replace
                    String[] command4 = {
                        "/usr/bin/replace", 
                        "site_name", 
                        siteName,
                        "--",
                        siteDir+"/bin/jboss",
                        siteDir+"/bin/profile.jboss",
                        siteDir+"/bin/profile.user"
                    };
                    AOServDaemon.exec(command4);

                    ln(".", siteDir+"/tomcat", uid, gid);
                }

                // try to install content source archive
                String rootDir = siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE;
                mkdir(rootDir, 0775, lsa, lsg);

                boolean archiveInstalled = installArchive(tomcatSite.getHttpdSite(), rootDir);

                if (!archiveInstalled) {		
                    // if content fails or no content...
                    if(!isTomcat4) mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/META-INF", 0775, lsa, lsg);
                    mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF", 0775, lsa, lsg);
                    mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/classes", 0770, lsa, lsg);
                    if(!isTomcat4) mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/cocoon", 0770, lsa, lsg);
                    if(!isTomcat4) mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/conf", 0770, lsa, lsg);
                    mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/lib", 0770, lsa, lsg);	
                    mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/cgi-bin", 0755, lsa, lsg);
                }
                //mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/analog", 0775, lsa, lsg);

                if (isStandard || isJBoss) {

                    mkdir(siteDir+"/work", 0750, lsa, lsg);

                    if(isTomcat4) {
                        ln("../../.."+tomcatDirectory+"/bin/bootstrap.jar", siteDir+"/bin/bootstrap.jar", uid, gid);
                        ln("../../.."+tomcatDirectory+"/bin/catalina.sh", siteDir+"/bin/catalina.sh", uid, gid);
                        //UnixFile catalinaUF=new UnixFile(siteDir+"/bin/catalina.sh");
                        //new UnixFile(tomcatDirectory+"/bin/catalina.sh").copyTo(catalinaUF, false);
                        //catalinaUF.chown(uid, gid).setMode(0700);
                        ln("../../.."+tomcatDirectory+"/bin/commons-daemon.jar", siteDir+"/bin/commons-daemon.jar", uid, gid);
                        if(isTomcat55) ln("../../.."+tomcatDirectory+"/bin/commons-logging-api.jar", siteDir+"/bin/commons-logging-api.jar", uid, gid);
                        ln("../../.."+tomcatDirectory+"/bin/digest.sh", siteDir+"/bin/digest.sh", uid, gid);
                        if(!isTomcat55) ln("../../.."+tomcatDirectory+"/bin/jasper.sh", siteDir+"/bin/jasper.sh", uid, gid);
                        if(!isTomcat55) ln("../../.."+tomcatDirectory+"/bin/jspc.sh", siteDir+"/bin/jspc.sh", uid, gid);
                    }

                    /*
                     * Set up the bash profile source
                     */
                    String profileFile=siteDir+(isJBoss?"/bin/profile.jboss":"/bin/profile");
                    LinuxAccountManager.setBashProfile(lsa, profileFile);

                    /*
                     * Write the profile script for standard only
                     */
                    if(isStandard) {
                        ChainWriter out=new ChainWriter(
                            new BufferedOutputStream(
                                new UnixFile(profileFile).getSecureOutputStream(
                                    uid,
                                    gid,
                                    0750,
                                    false
                                )
                            )
                        );
			try {
			    out.print("#!/bin/sh\n"
				      + "\n");
			    if(isTomcat4) {
				out.print(". /etc/profile\n");
                                if(isTomcat55) {
                                    out.print(". /usr/aoserv/etc/").print(DEFAULT_TOMCAT_5_JDK_VERSION).print(".sh\n");
                                } else {
                                    out.print(". /usr/aoserv/etc/").print(getDefaultJdkVersion(osv)).print(".sh\n");
                                }
                                out.print(". /usr/aoserv/etc/php-").print(getDefaultPhpVersion(osv)).print(".sh\n");
				if(postgresServerMinorVersion!=null) out.print(". /usr/aoserv/etc/postgresql-").print(postgresServerMinorVersion).print(".sh\n");
				out.print(". /usr/aoserv/etc/aoserv.sh\n"
                                        + "\n"
                                        + "umask 002\n"
                                        + "export DISPLAY=:0.0\n"
                                        + "\n"
                                        + "export CATALINA_HOME=\"").print(siteDir).print("\"\n"
                                        + "export CATALINA_BASE=\"").print(siteDir).print("\"\n"
                                        + "export CATALINA_TEMP=\"").print(siteDir).print("/temp\"\n"
                                );
			    } else if(tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_3_1_PREFIX)) {
				out.print(". /etc/profile\n"
					  + ". /usr/aoserv/etc/").print(getDefaultJdkVersion(osv)).print(".sh\n"
					  + ". /usr/aoserv/etc/jakarta-oro-2.0.sh\n"
					  + ". /usr/aoserv/etc/jakarta-regexp-1.1.sh\n"
					  + ". /usr/aoserv/etc/jakarta-servletapi-3.1.sh\n"
					  + ". /usr/aoserv/etc/jakarta-tomcat-3.1.sh\n"
					  + ". /usr/aoserv/etc/jetspeed-1.1.sh\n"
					  + ". /usr/aoserv/etc/cocoon-1.8.2.sh\n"
					  + ". /usr/aoserv/etc/xerces-1.2.0.sh\n"
					  + ". /usr/aoserv/etc/ant-1.6.2.sh\n"
					  + ". /usr/aoserv/etc/xalan-1.2.d02.sh\n"
					  + ". /usr/aoserv/etc/php-").print(getDefaultPhpVersion(osv)).print(".sh\n");
				if(postgresServerMinorVersion!=null) out.print(". /usr/aoserv/etc/postgresql-").print(postgresServerMinorVersion).print(".sh\n");
				out.print(". /usr/aoserv/etc/profile\n"
					  + ". /usr/aoserv/etc/fop-0.15.0.sh\n"
					  );
			    } else if(tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_3_2_PREFIX)) {
				out.print(". /etc/profile\n"
					  + ". /usr/aoserv/etc/").print(getDefaultJdkVersion(osv)).print(".sh\n"
					  + ". /usr/aoserv/etc/jakarta-oro-2.0.sh\n"
					  + ". /usr/aoserv/etc/jakarta-regexp-1.1.sh\n"
					  + ". /usr/aoserv/etc/jakarta-servletapi-3.2.sh\n"
					  + ". /usr/aoserv/etc/jakarta-tomcat-3.2.sh\n"
					  + ". /usr/aoserv/etc/jetspeed-1.1.sh\n"
					  + ". /usr/aoserv/etc/cocoon-1.8.2.sh\n"
					  + ". /usr/aoserv/etc/xerces-1.2.0.sh\n"
					  + ". /usr/aoserv/etc/ant-1.6.2.sh\n"
					  + ". /usr/aoserv/etc/xalan-1.2.d02.sh\n"
					  + ". /usr/aoserv/etc/php-").print(getDefaultPhpVersion(osv)).print(".sh\n");
				if(postgresServerMinorVersion!=null) out.print(". /usr/aoserv/etc/postgresql-").print(postgresServerMinorVersion).print(".sh\n");
				out.print(". /usr/aoserv/etc/profile\n"
					  + ". /usr/aoserv/etc/fop-0.15.0.sh\n"
					  );
			    } else throw new IllegalArgumentException("Unknown HttpdTomcatVersion: "+tomcatVersion);
			    out.print("\n"
				      + "export PATH=${PATH}:").print(siteDir).print("/bin\n"
										     + "\n");
			    if(!tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)) {
				out.print("CLASSPATH=${CLASSPATH}:").print(siteDir).print("/classes\n"
											  + "\n"
											  + "for i in ").print(siteDir).print("/lib/* ; do\n"
															      + "    if [ -f $i ]; then\n"
															      + "        CLASSPATH=${CLASSPATH}:$i\n"
															      + "    fi\n"
															      + "done\n"
															      + "\n");
			    }
			    out.print("export CLASSPATH\n");
			} finally {
			    out.flush();
			    out.close();
			}

                        /*
                         * Write the bin/tomcat script.
                         */
                        String tomcatScript=siteDir+"/bin/tomcat";
                        out=new ChainWriter(
                            new BufferedOutputStream(
                                new UnixFile(tomcatScript).getSecureOutputStream(
                                    uid,
                                    gid,
                                    0700,
                                    false
                                )
                            )
                        );
			try {
			    out.print("#!/bin/sh\n"
                                    + "\n"
                                    + "TOMCAT_HOME=").print(siteDir).print("\n"
                                    + "\n"
                                    + "if [ \"$1\" = \"start\" ]; then\n"
                                    + "    $0 stop\n"
                                    + "    $0 daemon &\n"
                                    + "    echo $! >${TOMCAT_HOME}/var/run/tomcat.pid\n"
                                    + "elif [ \"$1\" = \"stop\" ]; then\n"
                                    + "    if [ -f ${TOMCAT_HOME}/var/run/tomcat.pid ]; then\n"
                                    + "        kill `cat ${TOMCAT_HOME}/var/run/tomcat.pid`\n"
                                    + "        rm -f ${TOMCAT_HOME}/var/run/tomcat.pid\n"
                                    + "    fi\n"
                                    + "    if [ -f ${TOMCAT_HOME}/var/run/java.pid ]; then\n"
                                    + "        cd $TOMCAT_HOME\n"
                                    + "        . $TOMCAT_HOME/bin/profile\n"
                                    + "        umask 002\n"
                                    + "        export DISPLAY=:0.0\n");
                            if(!tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_5_PREFIX)) {
                                out.print("        ulimit -S -m 196608 -v 400000\n"
                                        + "        ulimit -H -m 196608 -v 400000\n");
                            }
			    if(isTomcat4) {
				out.print("        ${TOMCAT_HOME}/bin/catalina.sh stop 2>&1 >>${TOMCAT_HOME}/var/log/tomcat_err\n");
			    } else out.print("        java -Dmail.smtp.host=").print(thisAOServer.getServer().getHostname()).print(" org.apache.tomcat.startup.Tomcat -f ${TOMCAT_HOME}/conf/server.xml -stop &>/dev/null\n");
			    out.print("        kill `cat ${TOMCAT_HOME}/var/run/java.pid` &>/dev/null\n"
                                    + "        rm -f ${TOMCAT_HOME}/var/run/java.pid\n"
                                    + "    fi\n"
                                    + "elif [ \"$1\" = \"daemon\" ]; then\n"
                                    + "    cd $TOMCAT_HOME\n"
                                    + "    . $TOMCAT_HOME/bin/profile\n"
                                    + "\n"
                                    + "    while [ 1 ]; do\n");
                            if(!tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_5_PREFIX)) {
                                out.print("        ulimit -S -m 196608 -v 400000\n"
                                        + "        ulimit -H -m 196608 -v 400000\n");
                            }
                            out.print("        umask 002\n"
                                    + "        export DISPLAY=:0.0\n");
			    if(isTomcat4) {
				out.print("        mv -f ${TOMCAT_HOME}/var/log/tomcat_err ${TOMCAT_HOME}/var/log/tomcat_err.old\n"
					  + "        ${TOMCAT_HOME}/bin/catalina.sh run >&${TOMCAT_HOME}/var/log/tomcat_err &\n");
			    } else out.print("        java -Dmail.smtp.host=").print(thisAOServer.getServer().getHostname()).print(" org.apache.tomcat.startup.Tomcat -f ${TOMCAT_HOME}/conf/server.xml &>var/log/servlet_err &\n");
			    out.print("        echo $! >${TOMCAT_HOME}/var/run/java.pid\n"
				      + "        wait\n"
				      + "        RETCODE=$?\n"
				      + "        echo \"`date`: JVM died with a return code of $RETCODE, restarting in 5 seconds\" >>${TOMCAT_HOME}/var/log/jvm_crashes.log\n"
				      + "        sleep 5\n"
				      + "    done\n"
				      + "else\n"
				      + "    echo \"Usage:\"\n"
				      + "    echo \"tomcat {start|stop}\"\n"
				      + "    echo \"        start - start tomcat\"\n"
				      + "    echo \"        stop  - stop tomcat\"\n"
				      + "fi\n"
				      );
			} finally {
			    out.flush();
			    out.close();
			}
                    }
                    if(isTomcat4) {
                        ln("../../.."+tomcatDirectory+"/bin/setclasspath.sh", siteDir+"/bin/setclasspath.sh", uid, gid);

			UnixFile shutdown=new UnixFile(siteDir+"/bin/shutdown.sh");
                        ChainWriter out=new ChainWriter(shutdown.getSecureOutputStream(uid, gid, 0700, true));
			try {
			    out.print("#!/bin/sh\n"
                                    + "exec ").print(siteDir).print("/bin/tomcat stop\n");
			} finally {
			    out.flush();
			    out.close();
			}

			UnixFile startup=new UnixFile(siteDir+"/bin/startup.sh");
			out=new ChainWriter(startup.getSecureOutputStream(uid, gid, 0700, true));
			try {
			    out.print("#!/bin/sh\n"
                                    + "exec ").print(siteDir).print("/bin/tomcat start\n");
			} finally {
			    out.flush();
			    out.close();
			}

                        if(tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)) ln("../../.."+tomcatDirectory+"/bin/tomcat-jni.jar", siteDir+"/bin/tomcat-jni.jar", uid, gid);
                        if(isTomcat55) ln("../../.."+tomcatDirectory+"/bin/tomcat-juli.jar", siteDir+"/bin/tomcat-juli.jar", uid, gid);
                        if(
                            tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)
                            || isTomcat55
                        ) ln("../../.."+tomcatDirectory+"/bin/tool-wrapper.sh", siteDir+"/bin/tool-wrapper.sh", uid, gid);
                        if(isTomcat55) ln("../../.."+tomcatDirectory+"/bin/version.sh", siteDir+"/bin/version.sh", uid, gid);

                        /*
                         * Create the common directory and all contents
                         */
                        mkdir(siteDir+"/common", 0775, lsa, lsg);
                        mkdir(siteDir+"/common/classes", 0775, lsa, lsg);
                        
                        mkdir(siteDir+"/common/endorsed", 0775, lsa, lsg);
                        lnAll("../../../.."+tomcatDirectory+"/common/endorsed/", siteDir+"/common/endorsed/", uid, gid);

                        if(isTomcat55) {
                            mkdir(siteDir+"/common/i18n", 0775, lsa, lsg);
                            lnAll("../../../.."+tomcatDirectory+"/common/i18n/", siteDir+"/common/i18n/", uid, gid);
                        }

                        mkdir(siteDir+"/common/lib", 0775, lsa, lsg);
                        lnAll("../../../.."+tomcatDirectory+"/common/lib/", siteDir+"/common/lib/", uid, gid);
                        
                        if(postgresServerMinorVersion!=null) ln("../../../../usr/postgresql/"+postgresServerMinorVersion+"/share/java/postgresql.jar", siteDir+"/common/lib/postgresql.jar", uid, gid);
                        if(osv==OperatingSystemVersion.MANDRAKE_10_1_I586) ln("../../../../usr/mysql-connector-java/3.1.12/mysql-connector-java-3.1.12-bin.jar", siteDir+"/common/lib/mysql-connector-java-3.1.12-bin.jar", uid, gid);
                        else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

                        /*
                         * Write the conf/catalina.policy file
                         */
                        {
                            UnixFile cp=new UnixFile(siteDir+"/conf/catalina.policy");
                            new UnixFile(tomcatDirectory+"/conf/catalina.policy").copyTo(cp, false);
                            cp.chown(uid, gid).setMode(0660);
                        }
                        
                        if(isTomcat55) {
                            UnixFile cp=new UnixFile(siteDir+"/conf/catalina.properties");
                            new UnixFile(tomcatDirectory+"/conf/catalina.properties").copyTo(cp, false);
                            cp.chown(uid, gid).setMode(0660);
                        }
                        if(isTomcat55) {
                            UnixFile cp=new UnixFile(siteDir+"/conf/context.xml");
                            new UnixFile(tomcatDirectory+"/conf/context.xml").copyTo(cp, false);
                            cp.chown(uid, gid).setMode(0660);
                        }
                        if(isTomcat55) {
                            UnixFile cp=new UnixFile(siteDir+"/conf/logging.properties");
                            new UnixFile(tomcatDirectory+"/conf/logging.properties").copyTo(cp, false);
                            cp.chown(uid, gid).setMode(0660);
                        }
                    }
                    /*
                     * The classes directory
                     */
                    if(
                        !tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)
                        && !isTomcat55
                    ) mkdir(siteDir+"/classes", 0770, lsa, lsg);
                }

                /*
                 * Write the conf/group file.
                 */
                /*
                String confGroup=siteDir+"/conf/group";
                ChainWriter out=new ChainWriter(
                    new BufferedOutputStream(
                        new UnixFile(confGroup).getSecureOutputStream(uid, gid, 0644, false)
                    )
                );
		try {
		    out.print("developer: ").print(laUsername).print("\n");
		} finally {
		    out.flush();
		    out.close();
		}*/

                /*
                 * Write the manifest.servlet file.
                 */
                if(!isTomcat4) {
                    String confManifestServlet=siteDir+"/conf/manifest.servlet";
                    ChainWriter out=new ChainWriter(
                        new BufferedOutputStream(
                            new UnixFile(confManifestServlet).getSecureOutputStream(uid, gid, 0660, false)
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
			out.flush();
			out.close();
		    }
                }

                /*
                 * Create an empty password list file.
                 */
                //String confPasswd=siteDir+"/conf/passwd";
                //new UnixFile(confPasswd).getSecureOutputStream(uid, gid, 0644, false).close();

                /*
                 * Create the conf/server.dtd file.
                 */
                if(tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_3_1_PREFIX)
                    || tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_3_2_PREFIX)) {
                    String confServerDTD=siteDir+"/conf/server.dtd";
                    ChainWriter out=new ChainWriter(
                        new BufferedOutputStream(
                            new UnixFile(confServerDTD).getSecureOutputStream(uid, gid, 0660, false)
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
			out.flush();
			out.close();
		    }
                }

                /*
                 * Create the test-tomcat.xml file.
                 */
                if(!isTomcat4) {
                    String confTestTomcat=siteDir+"/conf/test-tomcat.xml";
                    copyResource("test-tomcat.xml", confTestTomcat, uid, gid, 0660);
                }

                /*
                 * Create the tomcat-users.xml file
                 */
                if(isTomcat4) {
                    if(isStandard) {
                        UnixFile tu=new UnixFile(siteDir+"/conf/tomcat-users.xml");
                        new UnixFile(tomcatDirectory+"/conf/tomcat-users.xml").copyTo(tu, false);
                        tu.chown(uid, gid).setMode(0660);
                    }
                } else {
                    String confTomcatUsers=siteDir+"/conf/tomcat-users.xml";
                    ChainWriter out=new ChainWriter(
                        new BufferedOutputStream(
                            new UnixFile(confTomcatUsers).getSecureOutputStream(uid, gid, 0660, false)
                        )
                    );
		    try {
			if(
			   tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_3_1_PREFIX)
			   || tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_3_2_PREFIX)
			   ) {
			    out.print("<tomcat-users>\n"
				      + "  <user name=\"tomcat\" password=\"tomcat\" roles=\"tomcat\" />\n"
				      + "</tomcat-users>\n"
				      );
			} else if(tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_3_2_PREFIX)) {
			    out.print("<tomcat-users>\n"
				      + "  <user name=\"tomcat\" password=\"tomcat\" roles=\"tomcat\" />\n"
				      + "  <user name=\"role1\"  password=\"tomcat\" roles=\"role1\" />\n"
				      + "  <user name=\"both\"   password=\"tomcat\" roles=\"tomcat,role1\" />\n"
				      + "</tomcat-users>\n"
				      );
			} else throw new IllegalArgumentException("Unsupported version of Tomcat: "+tomcatVersion);
		    } finally {
			out.flush();
			out.close();
		    }
                }

                /*
                 * Create the web.dtd file.
                 */
                if(!isTomcat4) {
                    String confWebDTD=siteDir+"/conf/web.dtd";
                    copyResource("web.dtd-"+tomcatVersion, confWebDTD, uid, gid, 0660);
                }

                /*
                 * Create the web.xml file.
                 */
                if(isTomcat4) {
                    if(isStandard) {
                        UnixFile wx=new UnixFile(siteDir+"/conf/web.xml");
                        new UnixFile(tomcatDirectory+"/conf/web.xml").copyTo(wx, false);
                        wx.chown(uid, gid).setMode(0660);
                    }
                } else {
                    String confWebXML=siteDir+"/conf/web.xml";
                    copyResource("web.xml-"+tomcatVersion, confWebXML, uid, gid, 0660);
                }

                if(isTomcat4 && isStandard) {
                    mkdir(siteDir+"/server", 0775, lsa, lsg);
                    mkdir(siteDir+"/server/classes", 0775, lsa, lsg);
                    mkdir(siteDir+"/server/lib", 0775, lsa, lsg);
                    lnAll("../../../.."+tomcatDirectory+"/server/lib/", siteDir+"/server/lib/", uid, gid);
                    if(
                        tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)
                        || isTomcat55
                    ) {
                        mkdir(siteDir+"/server/webapps", 0775, lsa, lsg);
                    }

                    /*
                     * The shared directory
                     */
                    if(
                        tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)
                        || isTomcat55
                    ) {
                        mkdir(siteDir+"/shared", 0775, lsa, lsg);
                        mkdir(siteDir+"/shared/classes", 0775, lsa, lsg);
                        mkdir(siteDir+"/shared/lib", 0775, lsa, lsg);
                    }
                }

                /*
                 * Create the empty log files.
                 */
                if(!isTomcat4) {
                    for(int c=0;c<tomcatLogFiles.length;c++) {
                        String filename=siteDir+"/var/log/"+tomcatLogFiles[c];
                        new UnixFile(filename).getSecureOutputStream(
                            isShared?shrSite.getHttpdSharedTomcat().getLinuxServerAccount().getUID().getID():uid,
                            isShared?shrSite.getHttpdSharedTomcat().getLinuxServerGroup().getGID().getID():gid,
                            isShared && shrSite.getHttpdSharedTomcat().isSecure()?0640:0660,
                            false
                        ).close();
                    }
                }

                if (!archiveInstalled) {
                    /*
                     * Create the manifest file.
                     */
                    if(!isTomcat4) {
                        String manifestFile=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/META-INF/MANIFEST.MF";
                        new ChainWriter(
                            new UnixFile(manifestFile).getSecureOutputStream(
                                uid,
                                gid,
                                0664,
                                false
                            )
                        ).print("Manifest-Version: 1.0").flush().close();
                    }

                    /*
                     * Create the test servlet source and compile
                     */
                    String servletHello=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/classes/Hello.java";
                    ChainWriter out=new ChainWriter(
                        new BufferedOutputStream(
                            new UnixFile(servletHello).getSecureOutputStream(uid, gid, 0660, false)
                        )
                    );
		    try {
			out.print("import java.io.*;\n"
				  + "import javax.servlet.*;\n"
				  + "import javax.servlet.http.*;\n"
				  + "\n"
				  + "public class Hello extends HttpServlet {\n"
				  + "\n"
				  + "    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {\n"
				  + "        resp.setContentType(\"text/html\");\n"
				  + "        PrintWriter out=resp.getWriter();\n"
				  + "        out.println(\"<HTML>\");\n"
				  + "        out.println(\"  <HEAD><TITLE>Test Servlet for ").print(primaryUrl).print("</TITLE></HEAD>\");\n"
														      + "        out.println(\"  <BODY bgcolor='#FFFFFF'>\");\n"
														      + "        out.println(\"    <H1>Test Servlet for ").print(primaryUrl).print("</H1>\");\n"
																								   + "        out.println(\"    <H2>Congratulations, Jakarta-Tomcat is working!\");\n"
																								   + "        out.println(\"  </BODY>\");\n"
																								   + "        out.println(\"</HTML>\");\n"
																								   + "        out.close();\n"
																								   + "    }\n"
																								   + "}\n"
																								   );
		    } finally {
			out.flush();
			out.close();
		    }
                    // Compile
                    String[] command=new String[] {getDefaultJdkPath(osv)+"/bin/javac", "-classpath", "/usr/apache/jakarta/servletapi/3.2.2/lib/servlet.jar", servletHello};
                    Process P=Runtime.getRuntime().exec(command);
                    try {
                        int retCode=P.waitFor();
                        if(retCode!=0) throw new IOException("Unable to compile Hello.java, retCode="+retCode);
                    } catch(InterruptedException err) {
                        InterruptedIOException newErr=new InterruptedIOException("Unable to compile Hello.java");
                        newErr.initCause(err);
                        throw newErr;
                    }
                    new UnixFile(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/classes/Hello.class").chown(uid, gid).setMode(0660);

                    /*
                     * Write the cocoon.properties file.
                     */
                    if(!isTomcat4) {
                        String cocoonProps=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/conf/cocoon.properties";
                        OutputStream fileOut=new BufferedOutputStream(new UnixFile(cocoonProps).getSecureOutputStream(uid, gid, 0660, false));
			try {
			    copyResource("cocoon.properties.1", fileOut);
			    out=new ChainWriter(fileOut);
			    try {
				out.print("processor.xsp.repository = ").print(siteDir).print("/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/cocoon\n");
				out.flush();
				copyResource("cocoon.properties.2", fileOut);
			    } finally {
				out.flush();
			    }
			} finally {
			    fileOut.flush();
			    fileOut.close();
			}
                    }

                    /*
                     * Write the ROOT/WEB-INF/web.xml file.
                     */
                    String webXML=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/web.xml";
                    out=new ChainWriter(
                        new BufferedOutputStream(
                            new UnixFile(webXML).getSecureOutputStream(uid, gid, 0664, false)
                        )
                    );
		    try {
                        if(isTomcat55) {
                            out.print("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n"
                                    + "<web-app xmlns=\"http://java.sun.com/xml/ns/j2ee\"\n"
                                    + "    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                                    + "    xsi:schemaLocation=\"http://java.sun.com/xml/ns/j2ee http://java.sun.com/xml/ns/j2ee/web-app_2_4.xsd\"\n"
                                    + "    version=\"2.4\">\n"
                                    + "  <display-name>Welcome to Tomcat</display-name>\n"
                                    + "  <description>\n"
                                    + "     Welcome to Tomcat\n"
                                    + "  </description>\n"
                                    + "</web-app>\n");
                        } else if(isTomcat4) {
			    out.print("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n"
				      + "\n"
				      + "<!DOCTYPE web-app\n"
				      + "    PUBLIC \"-//Sun Microsystems, Inc.//DTD Web Application 2.3//EN\"\n"
				      + "    \"http://java.sun.com/j2ee/dtds/web-app_2_3.dtd\">\n"
				      + "\n"
				      + "<web-app>\n");
			    if(tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)) {
				out.print("  <display-name>Welcome to Tomcat</display-name>\n"
					  + "  <description>\n"
					  + "    Welcome to Tomcat\n"
					  + "  </description>\n");
			    }
			    out.print("</web-app>\n");
			} else {
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
			}
		    } finally {
			out.flush();
			out.close();
		    }
                }

                /*
                 * Protect the analog directory.
                 */
                /*
                String analogAccess=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/analog/.htaccess";
                out=new ChainWriter(
                    new BufferedOutputStream(
                        new UnixFile(analogAccess).getSecureOutputStream(uid, gid, 0664, false)
                    )
                );
		try {
		    out.print("AuthGroupFile ").print(siteDir).print("/conf/group\n"
                            + "AuthUserFile ").print(siteDir).print("/conf/passwd\n"
                            + "AuthName \"").print(siteDir).print(" log files\"\n"
                            + "AuthType Basic\n"
                            + "require group developer\n"
                    );
		} finally {
		    out.flush();
		    out.close();
		}*/

                if (!archiveInstalled) {
                    /*
                     * Create the PHP script.
                     */
                    String php=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/cgi-bin/php";
                    ChainWriter out=new ChainWriter(
                        new UnixFile(php).getSecureOutputStream(uid, gid, 0755, false)
                    );
		    try {
			out.print("#!/bin/sh\n"
				  + ". /usr/aoserv/etc/postgresql-"+PHP_POSTGRES_MINOR_VERSION+".sh\n"
				  + "exec /usr/php/").print(getDefaultPhpVersion(osv)).print("/bin/php \"$@\"\n");
		    } finally {
			out.flush();
			out.close();
		    }

                    /*
                     * Create the test CGI script.
                     */
                    String testCGI=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/cgi-bin/test";
                    out=new ChainWriter(
                        new BufferedOutputStream(
                            new UnixFile(testCGI).getSecureOutputStream(uid, gid, 0755, false)
                        )
                    );
		    try {
			out.print("#!/usr/bin/perl\n"
                                + "print \"Content-type: text/html\\n\";\n"
                                + "print \"\\n\";\n"
                                + "print \"<HTML>\\n\";\n"
                                + "print \"  <BODY>\\n\";\n"
                                + "print \"    <H1>Test CGI Script for ").print(primaryUrl).print("</H1>\\n\";\n"
                                + "print \"  </BODY>\\n\";\n"
                                + "print \"</HTML>\\n\";\n"
                        );
                    } finally {
			out.flush();
			out.close();
		    }

                    /*
                     * Create the index.html
                     */
                    String indexHTML=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/index.html";
                    out=new ChainWriter(
                        new BufferedOutputStream(
                            new UnixFile(indexHTML).getSecureOutputStream(uid, gid, 0664, false)
                        )
                    );
		    try {
			out.print("<HTML>\n"
                                + "  <HEAD><TITLE>Test HTML Page for ").print(primaryUrl).print("</TITLE></HEAD>\n"
                                + "  <BODY>\n"
                                + "    Test HTML Page for ").print(primaryUrl).print("\n"
                                + "  </BODY>\n"
                                + "</HTML>\n"
                        );
		    } finally {
			out.flush();
			out.close();
		    }

                    /*
                     * Create the test.php file.
                     */
                    String testPHP=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/test.php";
                    new ChainWriter(
                        new UnixFile(testPHP).getSecureOutputStream(uid, gid, 0664, false)
                    ).print("<?phpinfo()?>\n").flush().close();
                }

                /*
                 * Create the log file analysis.
                 */
                // Shared config first
                //String analogShared=siteDir+"/conf/analog.shared";
		//String fullPrimaryURL;
                //out=new ChainWriter(
                //    new UnixFile(analogShared).getSecureOutputStream(uid, gid, 0660, false)
                //);
		//try {
		//    fullPrimaryURL=site.getPrimaryHttpdSiteURL().getURL();
		//    out.print("HOSTNAME \"").print(fullPrimaryURL).print("\"\n"
		//	 + "DNSFILE ").print(siteDir).print("/var/analog.dns\n"
		//    + "DNSLOCKFILE ").print(siteDir).print("/var/analog.dns.lock\n"
		//																   );
		//} finally {
		//    out.flush();
		//    out.close();
		//}

                // Each different bind
                /*
                HttpdSiteBind[] binds=site.getHttpdSiteBinds(false);
                for(int c=0;c<binds.length;c++) {
                    HttpdSiteBind bind=binds[c];
                    String accessLog=bind.getAccessLog();
                    String errorLog=bind.getErrorLog();
                    NetBind netBind=bind.getHttpdBind().getNetBind();
                    NetPort port=netBind.getPort();
                    ProtocolTable protocolTable=connector.protocols;
                    String protocol=
                        port.equals(protocolTable.getProtocol(Protocol.HTTP))?"http"
                        :port.equals(protocolTable.getProtocol(Protocol.HTTPS))?"https"
                        :String.valueOf(port.getPort())
                    ;
                    String analogBind=siteDir+"/conf/analog."+protocol;
                    out=new ChainWriter(
                        new UnixFile(analogBind).getSecureOutputStream(uid, gid, 0660, false)
                    );
		    try {
			out.print("CONFIGFILE ").print(siteDir).print("/conf/analog.shared\n");
			for(int d=9;d>0;d--) out.print("LOGFILE ").print(accessLog).print('.').print(d).print(".gz\n");
			out.print("LOGFILE ").print(accessLog).print("\n"
								     + "OUTFILE ").print(siteDir).print("/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/analog/%Y%M-").print(protocol).print(".html\n"
																							    + "HOSTURL \"").print(bind.getPrimaryHttpdSiteURL().getURL()).print("\"\n"
																																+ "ERRFILE ").print(siteDir).print("/var/log/analog.").print(protocol).print(".err\n"
																																									     );
		    } finally {
			out.flush();
			out.close();
		    }
                }
                // The analog.all config.
                String analogAll=siteDir+"/conf/analog.all";
                out=new ChainWriter(
                    new BufferedOutputStream(
                        new UnixFile(analogAll).getSecureOutputStream(uid, gid, 0660, false)
                    )
                );
		try {
		    out.print("CONFIGFILE ").print(siteDir).print("/conf/analog.shared\n");
		    for(int c=9;c>0;c--) {
			for(int d=0;d<binds.length;d++) {
			    out.print("LOGFILE ").print(binds[d].getAccessLog()).print('.').print(c).print(".gz\n");
			}
		    }
		    for(int d=0;d<binds.length;d++) {
			out.print("LOGFILE ").print(binds[d].getAccessLog()).print("\n");
		    }
		    out.print("OUTFILE ").print(siteDir).print("/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/analog/%Y%M-all.html\n"
							       + "HOSTURL \"").print(fullPrimaryURL).print("\"\n"
													   + "ERRFILE ").print(siteDir).print("/var/log/analog.all.err\n"
																	      );
		} finally {
		    out.flush();
		    out.close();
		}
                */

                /*
                 * Tell the system that everything has completed successfully.
                 */
                return true;
            } else {
                return false;
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static final void copyResource(String resource, OutputStream out) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "copyResource(String,OutputStream)", null);
        try {
            InputStream in=HttpdManager.class.getResourceAsStream(resource);
	    try {
		if(in==null) throw new IOException("Unable to find resource: "+resource);
		byte[] buff=BufferManager.getBytes();
		try {
		    int ret;
		    while((ret=in.read(buff, 0, BufferManager.BUFFER_SIZE))!=-1) out.write(buff, 0, ret);
		} finally {
		    BufferManager.release(buff);
		}
	    } finally {
		in.close();
	    }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static final void copyResource(String resource, String filename, int uid, int gid, int mode) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "copyResource(String,String,int,int,int)", null);
        try {
            OutputStream out=new UnixFile(filename).getSecureOutputStream(uid, gid, mode, false);
            try {
                copyResource(resource, out);
            } finally {
		out.flush();
                out.close();
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "doRebuild()", null);
        try {
            AOServConnector connector=AOServDaemon.getConnector();
            AOServer aoServer=AOServDaemon.getThisAOServer();

            int osv=aoServer.getServer().getOperatingSystemVersion().getPKey();
            // Currently only Mandrake 10.1 supported
            if(
                osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            synchronized(rebuildLock) {
                // Get some variables that will be used throughout the method
                List<File> deleteFileList=new ArrayList<File>();
                IntList serversNeedingRestarted=new SortedIntArrayList();
                IntList sharedTomcatsNeedingRestarted=new SortedIntArrayList();
                IntList sitesNeedingRestarted=new SortedIntArrayList();

                doRebuildSharedTomcats(aoServer, deleteFileList, connector, sharedTomcatsNeedingRestarted);
                doRebuildHttpdSites(aoServer, deleteFileList, connector, serversNeedingRestarted, sitesNeedingRestarted, sharedTomcatsNeedingRestarted);
                doRebuildHttpdServers(aoServer, deleteFileList, connector, serversNeedingRestarted);
                disableAndEnableSharedTomcats(aoServer, connector, sharedTomcatsNeedingRestarted);
                disableAndEnableHttpdSites(aoServer, connector, sitesNeedingRestarted);
                disableAndEnableSiteBinds(aoServer, connector);

                // Control the /etc/logrotate.d and /etc/httpd/conf/logrotate.d files
                doRebuildLogrotate(aoServer, deleteFileList, connector);

                // Reload the server configs
                reload(serversNeedingRestarted);

                /*
                 * Back up the files scheduled for removal.
                 */
                int deleteFileListLen=deleteFileList.size();
                if(deleteFileListLen>0) {
                    // Get the next backup filename
                    File backupFile=BackupManager.getNextBackupFile();
                    BackupManager.backupFiles(deleteFileList, backupFile);

                    /*
                     * Remove the files that have been backed up.
                     */
                    for(int c=0;c<deleteFileListLen;c++) {
                        File file=deleteFileList.get(c);
                        new UnixFile(file).secureDeleteRecursive();
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Only called by the internally synchronized <code>doRebuild()</code> method.
     */
    private static void doRebuildHttpdServers(AOServer aoServer, List<File> deleteFileList, AOServConnector conn, IntList serversNeedingRestarted) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "doRebuildHttpdServers(AOServer,List<File>,AOServConnector,IntList)", null);
        try {
            for(HttpdServer hs : aoServer.getHttpdServers()) {
                final int serverNum=hs.getNumber();

                /*
                 * Rebuild the httpd.conf file
                 */
                UnixFile httpdConfFileNew=new UnixFile(HTTPD_CONF_BEGINNING+serverNum+HTTPD_CONF_ENDING_NEW);
                ChainWriter out=new ChainWriter(
                    new BufferedOutputStream(
                        httpdConfFileNew.getSecureOutputStream(
                            UnixFile.ROOT_UID,
                            UnixFile.ROOT_GID,
                            0644,
                            true
                        )
                    )
                );
		try {

		    // The version of PHP module to run
		    TechnologyVersion phpVersion=hs.getModPhpVersion();

		    LinuxServerAccount lsa=hs.getLinuxServerAccount();
		    boolean isEnabled=lsa.getDisableLog()==null;

                // Standard beginning
                out.print("PidFile /var/run/httpd").print(serverNum).print(".pid\n"
                        + "ScoreBoardFile /var/run/httpd").print(serverNum).print(".scoreboard\n"
			+ "ServerName 127.0.0.1\n"
                        + "ErrorLog /var/log/httpd").print(serverNum).print("/error_log\n"
                        + "\n"
                        + "ServerType standalone\n"
                        + "ServerRoot \"/etc/httpd\"\n"
                        + "LockFile /var/lock/httpd.lock\n"
                        + "ResourceConfig conf/srm.conf\n"
                        + "AccessConfig conf/access.conf\n"
                        + "TimeOut ").print(hs.getTimeOut()).print("\n"
                        + "KeepAlive On\n"
                        + "MaxKeepAliveRequests 100\n"
                        + "KeepAliveTimeout 15\n"
                        + "MinSpareServers ").print(10).print("\n" //(servers.length==1 || hs.isShared())?10:2).print("\n" // TODO: Make column for this
                        + "MaxSpareServers ").print(20).print("\n" //(servers.length==1 || hs.isShared())?20:10).print("\n" // TODO: Make column for this
                        + "StartServers ").print(20).print("\n" //(servers.length==1 || hs.isShared())?20:10).print("\n" // TODO: Make column for this
                        + "MaxClients ").print(200).print("\n" //(servers.length==1 || hs.isShared())?200:50).print("\n" // TODO: Make column for this
                        + "MaxRequestsPerChild 0\n"
                        + "\n"
                        + "Include conf/modules_load/mod_vhost_alias\n"
                        + "Include conf/modules_load/mod_env\n"
                        + "Include conf/modules_load/mod_log_config\n"
                        + "Include conf/modules_load/mod_log_agent\n"
                        + "Include conf/modules_load/mod_log_referer\n"
                        + "Include conf/modules_load/mod_mime\n"
                        + "Include conf/modules_load/mod_negotiation\n"
                        + "Include conf/modules_load/mod_status\n"
                        + "Include conf/modules_load/mod_info\n"
                        + "Include conf/modules_load/mod_include\n"
                        + "Include conf/modules_load/mod_autoindex\n"
                        + "Include conf/modules_load/mod_dir\n"
                        + "Include conf/modules_load/mod_cgi\n"
                        + "Include conf/modules_load/mod_asis\n"
                        + "Include conf/modules_load/mod_imap\n"
                        + "Include conf/modules_load/mod_actions\n"
                        //+ "Include conf/modules_load/mod_userdir\n"
                        + "Include conf/modules_load/mod_alias\n"
                        + "Include conf/modules_load/mod_rewrite\n"
                        + "Include conf/modules_load/mod_access\n"
                        + "Include conf/modules_load/mod_auth\n"
                        + "Include conf/modules_load/mod_auth_anon\n"
                        + "Include conf/modules_load/mod_auth_db\n"
                        + "Include conf/modules_load/libproxy\n"
                        + "Include conf/modules_load/mod_expires\n"
                        + "Include conf/modules_load/mod_headers\n"
                        + "Include conf/modules_load/mod_setenvif\n"
                        + "Include conf/modules_load/libssl\n"
                        + "Include conf/modules_load/libdav\n"
                        //+ "Include conf/modules_load/mod_fastcgi\n"
                        + "Include conf/modules_load/mod_").print(hs.isModJK()?"jk":"jserv").print("\n");
                if(isEnabled && hs.useModPERL()) out.print("Include conf/modules_load/mod_perl\n");
                if(isEnabled && phpVersion!=null) {
                    String version = phpVersion.getVersion();
                    out.print("LoadModule php"+version.charAt(0)+"_module /usr/php/"+getMajorPhpVersion(version)+"/lib/apache/"+getPhpLib(phpVersion)+"\n");
                }
                out.print("\n"
                        + "ClearModuleList\n"
                        + "AddModule mod_so.c\n"
                        + "\n"
                        + "Include conf/modules_init/mod_vhost_alias\n"
                        + "Include conf/modules_init/mod_env\n"
                        + "Include conf/modules_init/mod_log_config\n"
                        + "Include conf/modules_init/mod_log_agent\n"
                        + "Include conf/modules_init/mod_log_referer\n"
                        + "Include conf/modules_init/mod_mime\n"
                        + "Include conf/modules_init/mod_negotiation\n"
                        + "Include conf/modules_init/mod_status\n"
                        + "Include conf/modules_init/mod_info\n"
                        + "Include conf/modules_init/mod_include\n"
                        + "Include conf/modules_init/mod_autoindex\n"
                        + "Include conf/modules_init/mod_dir\n"
                        + "Include conf/modules_init/mod_cgi\n"
                        + "Include conf/modules_init/mod_asis\n"
                        + "Include conf/modules_init/mod_imap\n"
                        + "Include conf/modules_init/mod_actions\n"
                        //+ "Include conf/modules_init/mod_userdir\n"
                        + "Include conf/modules_init/mod_alias\n"
                        + "Include conf/modules_init/mod_rewrite\n"
                        + "Include conf/modules_init/mod_access\n"
                        + "Include conf/modules_init/mod_auth\n"
                        + "Include conf/modules_init/mod_auth_anon\n"
                        + "Include conf/modules_init/mod_auth_db\n"
                        + "Include conf/modules_init/libproxy\n"
                        + "Include conf/modules_init/mod_expires\n"
                        + "Include conf/modules_init/mod_headers\n"
                        + "Include conf/modules_init/mod_setenvif\n"
                        + "Include conf/modules_init/libssl\n"
                        + "Include conf/modules_init/libdav\n"
                        //+ "Include conf/modules_init/mod_fastcgi\n"
                        + "Include conf/modules_init/mod_").print(hs.isModJK()?"jk":"jserv").print("\n");
                if(isEnabled && hs.useModPERL()) out.print("Include conf/modules_init/mod_perl\n");
                if(isEnabled && phpVersion!=null) out.print("Include conf/modules_init/mod_php").print(getMajorPhpVersion(phpVersion.getVersion())).print('\n');
                out.print("\n"
                        + "Include conf/modules_conf/mod_vhost_alias\n"
                        + "Include conf/modules_conf/mod_env\n"
                        + "Include conf/modules_conf/mod_log_config\n"
                        + "Include conf/modules_conf/mod_log_agent\n"
                        + "Include conf/modules_conf/mod_log_referer\n"
                        + "Include conf/modules_conf/mod_mime\n"
                        + "Include conf/modules_conf/mod_negotiation\n"
                        + "Include conf/modules_conf/mod_status\n"
                        + "Include conf/modules_conf/mod_info\n"
                        + "Include conf/modules_conf/mod_include\n"
                        + "Include conf/modules_conf/mod_autoindex\n"
                        + "Include conf/modules_conf/mod_dir\n"
                        + "Include conf/modules_conf/mod_cgi\n"
                        + "Include conf/modules_conf/mod_asis\n"
                        + "Include conf/modules_conf/mod_imap\n"
                        + "Include conf/modules_conf/mod_actions\n"
                        //+ "Include conf/modules_conf/mod_userdir\n"
                        + "Include conf/modules_conf/mod_alias\n"
                        + "Include conf/modules_conf/mod_rewrite\n"
                        + "Include conf/modules_conf/mod_access\n"
                        + "Include conf/modules_conf/mod_auth\n"
                        + "Include conf/modules_conf/mod_auth_anon\n"
                        + "Include conf/modules_conf/mod_auth_db\n"
                        + "Include conf/modules_conf/libproxy\n"
                        + "Include conf/modules_conf/mod_expires\n"
                        + "Include conf/modules_conf/mod_headers\n"
                        + "Include conf/modules_conf/mod_setenvif\n"
                        + "Include conf/modules_conf/libssl\n"
                        + "Include conf/modules_conf/libdav\n"
                        //+ "Include conf/modules_conf/mod_fastcgi\n"
                        + "Include conf/modules_conf/mod_").print(hs.isModJK()?"jk":"jserv").print("\n");
                if(isEnabled && hs.useModPERL()) out.print("Include conf/modules_conf/mod_perl\n");
                if(isEnabled && phpVersion!=null) out.print("Include conf/modules_conf/mod_php").print(getMajorPhpVersion(phpVersion.getVersion())).print('\n');
                out.print("\n");
                // Use httpd if the account is disabled
                if(isEnabled) {
                    out.print("User ").print(lsa.getLinuxAccount().getUsername().getUsername()).print("\n"
                            + "Group ").print(hs.getLinuxServerGroup().getLinuxGroup().getName()).print("\n");
                } else {
                    out.print("User "+LinuxAccount.HTTPD+"\n"
                            + "Group "+LinuxGroup.HTTPD+"\n");
                }
                out.print("ServerAdmin support@aoindustries.com\n"
                        + "DocumentRoot \"/var/www/html\"\n"
                        + "<Directory />\n"
                        + "    AllowOverride None\n"
                        + "    Deny from All\n"
                        + "    Order deny,allow\n"
                        + "    Options None\n"
                        + "    AuthType Basic\n"
                        + "</Directory>\n"
                        + "<Directory /var/www/html>\n"
                        + "    Allow from All\n"
                        + "    Order allow,deny\n"
                        + "</Directory>\n"
                        + "AccessFileName .htaccess\n"
                        + "<Files ~ \"^\\.ht\">\n"
                        + "    Order allow,deny\n"
                        + "    Deny from all\n"
                        + "</Files>\n"
                        + "\n"
                        + "UseCanonicalName On\n"
                        + "DefaultType text/plain\n"
                        + "HostnameLookups Off\n"
                        + "LogLevel error\n"
                        + "ServerSignature Off\n"
                        + "ServerTokens Minimal\n"
                        + "\n"
                        + "<Location />\n"
                        + "    AuthType Basic\n"
                        + "</Location>\n"
                        + "\n"
                        + "CustomLog /var/log/httpd").print(serverNum).print("/access_log common\n"
                        + "RewriteLog /var/log/httpd").print(serverNum).print("/rewrite.log\n"
                        + "\n"
                        + "<IfModule mod_dav.c>\n"
                        + "    DAVLockDB /var/lock/libdav/httpd").print(serverNum).print("\n"
                        + "</IfModule>"
                        + "\n"
                        + "<IfModule mod_ssl.c>\n"
                        + "    SSLRandomSeed startup file:/dev/urandom 1024\n"
                        + "    SSLRandomSeed connect file:/dev/urandom 1024\n"
                        + "    SSLSessionCache shm:/var/log/httpd").print(serverNum).print("/ssl_scache(512000)\n"
                        + "    SSLLog /var/log/httpd").print(serverNum).print("/ssl_engine_log\n"
                        + "</IfModule>\n"
                        + "\n"
                        + "<IfModule mod_jserv.c>\n"
                        + "    ApJServLogFile /var/log/httpd").print(serverNum).print("/jserv.log\n"
                        + "</IfModule>\n"
                        + "\n"
                        + "<IfModule mod_jk.c>\n"
                        + "    JkWorkersFile /etc/httpd/conf/workers").print(serverNum).print(".properties\n"
                        + "    JkLogFile /var/log/httpd").print(serverNum).print("/mod_jk.log\n"
                        + "</IfModule>\n"
                        + "\n"
                );

                // List of binds
                for(HttpdBind hb : hs.getHttpdBinds()) {
                    NetBind nb=hb.getNetBind();
                    String ip=nb.getIPAddress().getIPAddress();
                    int port=nb.getPort().getPort();
                    out.print("Listen ").print(ip).print(':').print(port).print("\n"
                            + "NameVirtualHost ").print(ip).print(':').print(port).print('\n');
                }

                // The list of sites to include
                List<HttpdSite> sites=hs.getHttpdSites();
                for(int d=0;d<2;d++) {
                    boolean listFirst=d==0;
                    out.print("\n");
                    for(HttpdSite site : sites) {
                        if(site.listFirst()==listFirst) {
                            for(HttpdSiteBind bind : site.getHttpdSiteBinds(hs)) {
                                NetBind nb=bind.getHttpdBind().getNetBind();
                                String ipAddress=nb.getIPAddress().getIPAddress();
                                int port=nb.getPort().getPort();
                                out.print("Include conf/hosts/").print(site.getSiteName()).print('_').print(ipAddress).print('_').print(port).print('\n');
                            }
                        }
                    }
                }
		} finally {
		    out.flush();
		    out.close();
		}

                /*
                 * Rebuild the workers.properties file
                 */
                List<HttpdWorker> workers=hs.getHttpdWorkers();
                int workerCount=workers.size();

                UnixFile workersFileNew=new UnixFile(WORKERS_BEGINNING+serverNum+WORKERS_ENDING_NEW);
                out=new ChainWriter(
                    new BufferedOutputStream(
                        workersFileNew.getSecureOutputStream(
                            UnixFile.ROOT_UID,
                            UnixFile.ROOT_GID,
                            0644,
                            false
                        )
                    )
                );
		try {
		    out.print("worker.list=");
		    for(int d=0;d<workerCount;d++) {
			if(d>0) out.print(',');
			out.print(workers.get(d).getCode().getCode());
		    }
		    out.print('\n');
		    for(int d=0;d<workerCount;d++) {
			HttpdWorker worker=workers.get(d);
			String code=worker.getCode().getCode();
			out.print("\n"
                                + "worker.").print(code).print(".type=").print(worker.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol()).print("\n"
                                + "worker.").print(code).print(".port=").print(worker.getNetBind().getPort().getPort()).print('\n');
		    }
		} finally {
		    out.flush();
		    out.close();
		}

                /*
                 * Move the new files into place
                 */
                UnixFile httpdConfFile=new UnixFile(HTTPD_CONF_BEGINNING+serverNum+HTTPD_CONF_ENDING);
                boolean httpdConfFileExists=httpdConfFile.exists();
                UnixFile httpdConfFileOld=new UnixFile(HTTPD_CONF_BEGINNING+serverNum+HTTPD_CONF_ENDING_OLD);
                if(!httpdConfFileExists || !httpdConfFile.contentEquals(httpdConfFileNew)) {
                    int hsPKey=hs.getPKey();
                    if(!serversNeedingRestarted.contains(hsPKey)) serversNeedingRestarted.add(hsPKey);
                    if(httpdConfFileExists) httpdConfFile.renameTo(httpdConfFileOld);
                    httpdConfFileNew.renameTo(httpdConfFile);
                } else httpdConfFileNew.delete();

                UnixFile workersFile=new UnixFile(WORKERS_BEGINNING+serverNum+WORKERS_ENDING);
                boolean workersFileExists=workersFile.exists();
                UnixFile workersFileOld=new UnixFile(WORKERS_BEGINNING+serverNum+WORKERS_ENDING_OLD);
                if(!workersFileExists || !workersFile.contentEquals(workersFileNew)) {
                    int hsPKey=hs.getPKey();
                    if(!serversNeedingRestarted.contains(hsPKey)) serversNeedingRestarted.add(hsPKey);
                    if(workersFileExists) workersFile.renameTo(workersFileOld);
                    workersFileNew.renameTo(workersFile);
                } else workersFileNew.delete();
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Only called by the internally synchronized <code>doRebuild()</code> method.
     */
    private static void doRebuildHttpdSites(
        AOServer aoServer,
        List<File> deleteFileList,
        AOServConnector conn,
        IntList serversNeedingRestarted,
        IntList sitesNeedingRestarted,
        IntList sharedTomcatsNeedingRestarted
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "doRebuildHttpdSites(AOServer,List<File>,AOServConnector,IntList,IntList,IntList)", null);
        try {
            int osv=aoServer.getServer().getOperatingSystemVersion().getPKey();

            /*
             * Get values used in the rest of the method.
             */
            Username httpdUsername=conn.usernames.get(LinuxAccount.HTTPD);
            if(httpdUsername==null) throw new SQLException("Unable to find Username: "+LinuxAccount.HTTPD);
            LinuxAccount httpdLA=httpdUsername.getLinuxAccount();
            if(httpdLA==null) throw new SQLException("Unable to find LinuxAccount: "+httpdUsername);
            LinuxServerAccount httpdLSA=httpdLA.getLinuxServerAccount(aoServer);
            if(httpdLSA==null) throw new SQLException("Unable to find LinuxServerAccount: "+httpdLA+" on "+aoServer.getServer().getHostname());
            int httpdUID=httpdLSA.getUID().getID();
            LinuxServerAccount awstatsLSA=aoServer.getLinuxServerAccount(LinuxAccount.AWSTATS);
            if(awstatsLSA==null) throw new SQLException("Unable to find LinuxServerAccount: "+LinuxAccount.AWSTATS+" on "+aoServer.getServer().getHostname());
            int awstatsUID=awstatsLSA.getUID().getID();

            // Iterate through each site
            List<HttpdSite> sites=aoServer.getHttpdSites();

            // The log directories that exist but are not used will be removed
            String[] list=new File(LOG_DIR).list();
            List<String> logDirectories=new SortedArrayList<String>(list.length);
            for(int c=0;c<list.length;c++) {
                String dirname=list[c];
                if(
                    !dirname.equals("lost+found")
                    && !dirname.equals(".backup")
                ) logDirectories.add(dirname);
            }

            // The www directories that exist but are not used will be removed
            list=new File(HttpdSite.WWW_DIRECTORY).list();
            List<String> wwwDirectories=new SortedArrayList<String>(list.length);
            for(int c=0;c<list.length;c++) {
                String dirname=list[c];
                if(
                    !dirname.equals("lost+found")
                    && !dirname.equals(".backup")
                    && !dirname.equals("aquota.user")
                ) {
                    wwwDirectories.add(dirname);
                }
            }

            // The config directory should only contain files referenced in the database
            list=new File(CONF_HOSTS).list();
            List<String> hostConfFiles=new SortedArrayList<String>(list.length);
            for(int c=0;c<list.length;c++) hostConfFiles.add(list[c]);

            // Each site gets its own shared FTP space
            list=new File(FTPManager.SHARED_FTP_DIRECTORY).list();
            List<String> ftpDirectories=new SortedArrayList<String>(list.length);
            for(int c=0;c<list.length;c++) ftpDirectories.add(list[c]);

            int len=sites.size();
            for(int c=0;c<len;c++) {
                /*
                 * Get values used in the rest of the loop.
                 */
                HttpdSite site=sites.get(c);
                List<HttpdSiteBind> binds=site.getHttpdSiteBinds();
                final String siteName=site.getSiteName();
                String siteDir=HttpdSite.WWW_DIRECTORY+'/'+siteName;

                LinuxServerAccount lsa=site.getLinuxServerAccount();
                int lsaUID=lsa.getUID().getID();

                LinuxServerGroup lsg=site.getLinuxServerGroup();
                int lsgGID=lsg.getGID().getID();

                /*
                 * Take care of the log files and their rotation.
                 */
                UnixFile uf=new UnixFile(LOG_DIR+'/'+siteName);
                if(!uf.exists()) uf.mkdir();
                uf.chown(awstatsUID, lsgGID);
                uf.setMode(0750);

                // Make sure the newly created or existing log directories are not removed
                logDirectories.remove(siteName);

                /*
                 * Create and fill in the directory if it does not exist or is owned by root.
                 */
                HttpdTomcatSite tomcatSite=site.getHttpdTomcatSite();
                UnixFile wwwDirUF=new UnixFile(siteDir);
                if(!wwwDirUF.exists() || wwwDirUF.getUID()==UnixFile.ROOT_GID) {
                    HttpdStaticSite staticSite=site.getHttpdStaticSite();

                    if(!wwwDirUF.exists()) wwwDirUF.mkdir();
                    wwwDirUF.setMode(
                        (
                            tomcatSite!=null
                            && tomcatSite.getHttpdTomcatSharedSite()!=null
                            && !tomcatSite.getHttpdTomcatVersion().isTomcat4(conn)
                            && tomcatSite.getHttpdTomcatSharedSite().getHttpdSharedTomcat().isSecure()
                        ) ? 01770 : 0770
                    );

                    boolean isCreated;
                    if(staticSite!=null) {
                        buildStaticSite(staticSite, lsa, lsg);
                        isCreated=true;
                    } else {
                        if(tomcatSite!=null) isCreated=buildTomcatSite(tomcatSite, lsa, lsg);
                        else {
                            // If caught in the middle of account creation, this might occur
                            isCreated=false;
                        }
                    }
                    // Now that the contents are all successfully created, update the directory
                    // owners to make sure we don't cover up this directory in the future
                    if(isCreated) wwwDirUF.chown(httpdUID, lsgGID);
                }

                // Make sure the newly created or existing web site is not removed
                wwwDirectories.remove(siteName);

                /*
                 * Add or rebuild the server.xml files for standard or Tomcat sites.
                 */
                if(tomcatSite!=null) {
                    HttpdTomcatVersion htv=tomcatSite.getHttpdTomcatVersion();
                    boolean isTomcat4=htv.isTomcat4(conn);
                    boolean isTomcat55=htv.isTomcat55(conn);
                    HttpdTomcatStdSite stdSite=tomcatSite.getHttpdTomcatStdSite();
                    boolean isStandard=stdSite!=null;
                    boolean isJBoss=tomcatSite.getHttpdJBossSite()!=null;
                    if(
                        !isTomcat4
                        || isStandard
                        || isJBoss
                    ) {
                        HttpdTomcatSharedSite shrSite=tomcatSite.getHttpdTomcatSharedSite();
                        String autoWarning=getAutoWarning(site);
                        boolean isShared=shrSite!=null;

                        String confServerXML=siteDir+"/conf/server.xml";
                        UnixFile confServerXMLFile=new UnixFile(confServerXML);
                        if(!site.isManual() || !confServerXMLFile.exists()) {
                            UnixFile newConfServerXML=new UnixFile(confServerXML+".new");
                            try {
                                ChainWriter out=new ChainWriter(
                                    new BufferedOutputStream(
                                        newConfServerXML.getSecureOutputStream(
                                            isShared && shrSite.getHttpdSharedTomcat().isSecure()
                                            ?UnixFile.ROOT_UID
                                            :site.getLinuxServerAccount().getUID().getID(),
                                            site.getLinuxServerGroup().getGID().getID(),
                                            isShared && shrSite.getHttpdSharedTomcat().isSecure()
                                            ?0640
                                            :0660,
                                            true
                                        )
                                    )
                                );
				try {
                                    String tomcatVersion=tomcatSite.getHttpdTomcatVersion().getTechnologyVersion(conn).getVersion();
                                    if(tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_3_1_PREFIX)) {
                                        out.print("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n");
                                        if(!site.isManual()) out.print(autoWarning);
                                        out.print("<Server>\n"
                                                + "    <xmlmapper:debug level=\"0\" />\n"
                                                + "    <Logger name=\"tc_log\"\n"
                                                + "            path=\"").print(siteDir).print("/var/log/tomcat.log\"\n"
                                                + "            customOutput=\"yes\" />\n"
                                                + "    <Logger name=\"servlet_log\"\n"
                                                + "            path=\"").print(siteDir).print("/var/log/servlet.log\"\n"
                                                + "            customOutput=\"yes\" />\n"
                                                + "    <Logger name=\"JASPER_LOG\"\n"
                                                + "            path=\"").print(siteDir).print("/var/log/jasper.log\"\n"
                                                + "            verbosityLevel = \"INFORMATION\" />\n"
                                                + "    <ContextManager debug=\"0\" home=\"").print(siteDir).print("\" workDir=\"");
                                        if (isStandard) out.print(siteDir).print("/work");
                                        else if (isShared) out.print(HttpdSharedTomcat.WWW_GROUP_DIR+'/')
                                                .print(shrSite.getHttpdSharedTomcat().getName())
                                                .print("/work/").print(siteName);
                                        out.print("\" >\n"
                                                + "        <ContextInterceptor className=\"org.apache.tomcat.context.DefaultCMSetter\" />\n"
                                                + "        <ContextInterceptor className=\"org.apache.tomcat.context.WorkDirInterceptor\" />\n"
                                                + "        <ContextInterceptor className=\"org.apache.tomcat.context.WebXmlReader\" />\n"
                                                + "        <ContextInterceptor className=\"org.apache.tomcat.context.LoadOnStartupInterceptor\" />\n"
                                                + "        <RequestInterceptor className=\"org.apache.tomcat.request.SimpleMapper\" debug=\"0\" />\n"
                                                + "        <RequestInterceptor className=\"org.apache.tomcat.request.SessionInterceptor\" />\n"
                                                + "        <RequestInterceptor className=\"org.apache.tomcat.request.SecurityCheck\" />\n"
                                                + "        <RequestInterceptor className=\"org.apache.tomcat.request.FixHeaders\" />\n"
                                        );
                                        for(HttpdWorker worker : tomcatSite.getHttpdWorkers()) {
                                            NetBind netBind=worker.getNetBind();
                                            String protocol=worker.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol();

                                            out.print("        <Connector className=\"org.apache.tomcat.service.PoolTcpConnector\">\n"
                                                    + "            <Parameter name=\"handler\" value=\"");
                                            if(protocol.equals(HttpdJKProtocol.AJP12)) out.print("org.apache.tomcat.service.connector.Ajp12ConnectionHandler");
                                            else if(protocol.equals(HttpdJKProtocol.AJP13)) throw new IllegalArgumentException("Tomcat Version "+tomcatVersion+" does not support AJP version: "+protocol);
                                            else throw new IllegalArgumentException("Unknown AJP version: "+tomcatVersion);
                                            out.print("\"/>\n"
                                                    + "            <Parameter name=\"port\" value=\"").print(netBind.getPort()).print("\"/>\n");
                                            IPAddress ip=netBind.getIPAddress();
                                            if(!ip.isWildcard()) out.print("            <Parameter name=\"inet\" value=\"").print(ip.getIPAddress()).print("\"/>\n");
                                            out.print("            <Parameter name=\"max_threads\" value=\"30\"/>\n"
                                                    + "            <Parameter name=\"max_spare_threads\" value=\"10\"/>\n"
                                                    + "            <Parameter name=\"min_spare_threads\" value=\"1\"/>\n"
                                                    + "        </Connector>\n"
                                            );
                                        }
                                        for(HttpdTomcatContext htc : tomcatSite.getHttpdTomcatContexts()) {
                                            out.print("    <Context path=\"").print(htc.getPath()).print("\" docBase=\"").print(htc.getDocBase()).print("\" debug=\"").print(htc.getDebugLevel()).print("\" reloadable=\"").print(htc.isReloadable()).print("\" />\n");
                                        }
                                        out.print("  </ContextManager>\n"
                                                + "</Server>\n");
                                    } else if(tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_3_2_PREFIX)) {
                                        out.print("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n");
                                        if(!site.isManual()) out.print(autoWarning);
                                        out.print("<Server>\n"
                                                + "  <xmlmapper:debug level=\"0\" />\n"
                                                + "  <Logger name=\"tc_log\" verbosityLevel = \"INFORMATION\" path=\"").print(siteDir).print("/var/log/tomcat.log\" />\n"
                                                + "  <Logger name=\"servlet_log\" path=\"").print(siteDir).print("/var/log/servlet.log\" />\n"
                                                + "  <Logger name=\"JASPER_LOG\" path=\"").print(siteDir).print("/var/log/jasper.log\" verbosityLevel = \"INFORMATION\" />\n"
                                                + "\n"
                                                + "  <ContextManager debug=\"0\" home=\"").print(siteDir).print("\" workDir=\"");
                                        if(isStandard) out.print(siteDir).print("/work");
                                        else if(isShared) out.print(HttpdSharedTomcat.WWW_GROUP_DIR+'/')
                                                .print(shrSite.getHttpdSharedTomcat().getName())
                                                .print("/work/").print(siteName);
                                        out.print("\" showDebugInfo=\"true\" >\n"
                                                + "    <ContextInterceptor className=\"org.apache.tomcat.context.WebXmlReader\" />\n"
                                                + "    <ContextInterceptor className=\"org.apache.tomcat.context.LoaderInterceptor\" />\n"
                                                + "    <ContextInterceptor className=\"org.apache.tomcat.context.DefaultCMSetter\" />\n"
                                                + "    <ContextInterceptor className=\"org.apache.tomcat.context.WorkDirInterceptor\" />\n");
                                        if(isJBoss) out.print("    <RequestInterceptor className=\"org.apache.tomcat.request.Jdk12Interceptor\" />\n");
                                        out.print("    <RequestInterceptor className=\"org.apache.tomcat.request.SessionInterceptor\"");
                                        if(isJBoss) out.print(" noCookies=\"false\"");
                                        out.print(" />\n"
                                                + "    <RequestInterceptor className=\"org.apache.tomcat.request.SimpleMapper1\" debug=\"0\" />\n"
                                                + "    <RequestInterceptor className=\"org.apache.tomcat.request.InvokerInterceptor\" debug=\"0\" ");
                                        if(isJBoss) out.print("prefix=\"/servlet/\" ");
                                        out.print("/>\n"
                                                + "    <RequestInterceptor className=\"org.apache.tomcat.request.StaticInterceptor\" debug=\"0\" ");
                                        if(isJBoss) out.print("suppress=\"false\" ");
                                        out.print("/>\n"
                                                + "    <RequestInterceptor className=\"org.apache.tomcat.session.StandardSessionInterceptor\" />\n"
                                                + "    <RequestInterceptor className=\"org.apache.tomcat.request.AccessInterceptor\" debug=\"0\" />\n");
                                        if(isJBoss) out.print("    <RequestInterceptor className=\"org.jboss.tomcat.security.JBossSecurityMgrRealm\" />\n");
                                        else out.print("    <RequestInterceptor className=\"org.apache.tomcat.request.SimpleRealm\" debug=\"0\" />\n");
                                        out.print("    <ContextInterceptor className=\"org.apache.tomcat.context.LoadOnStartupInterceptor\" />\n");

                                        for(HttpdWorker worker : tomcatSite.getHttpdWorkers()) {
                                            NetBind netBind=worker.getNetBind();
                                            String protocol=worker.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol();

                                            out.print("    <Connector className=\"org.apache.tomcat.service.PoolTcpConnector\">\n"
                                                    + "      <Parameter name=\"handler\" value=\"");
                                            if(protocol.equals(HttpdJKProtocol.AJP12)) out.print("org.apache.tomcat.service.connector.Ajp12ConnectionHandler");
                                            else if(protocol.equals(HttpdJKProtocol.AJP13)) out.print("org.apache.tomcat.service.connector.Ajp13ConnectionHandler");
                                            else throw new IllegalArgumentException("Unknown AJP version: "+tomcatVersion);
                                            out.print("\"/>\n"
                                                    + "      <Parameter name=\"port\" value=\"").print(netBind.getPort()).print("\"/>\n");
                                            IPAddress ip=netBind.getIPAddress();
                                            if(!ip.isWildcard()) out.print("      <Parameter name=\"inet\" value=\"").print(ip.getIPAddress()).print("\"/>\n");
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
                                    } else if(isTomcat4) {
                                        List<HttpdWorker> hws=tomcatSite.getHttpdWorkers();
                                        if(hws.size()!=1) throw new SQLException("Expected to only find one HttpdWorker for HttpdTomcatStdSite #"+site.getPKey()+", found "+hws.size());
                                        HttpdWorker hw=hws.get(0);
                                        String hwProtocol=hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol();
                                        if(!hwProtocol.equals(HttpdJKProtocol.AJP13)) {
                                            throw new SQLException("HttpdWorker #"+hw.getPKey()+" for HttpdTomcatStdSite #"+site.getPKey()+" must be AJP13 but it is "+hwProtocol);
                                        }
                                        if(!site.isManual()) out.print(autoWarning);
                                        NetBind shutdownPort=stdSite.getTomcat4ShutdownPort();
                                        if(shutdownPort==null) throw new SQLException("Unable to find shutdown port for HttpdTomcatStdSite="+stdSite);
                                        String shutdownKey=stdSite.getTomcat4ShutdownKey();
                                        if(shutdownKey==null) throw new SQLException("Unable to find shutdown key for HttpdTomcatStdSite="+stdSite);
                                        out.print("<Server port=\"").print(shutdownPort.getPort().getPort()).print("\" shutdown=\"").print(shutdownKey).print("\" debug=\"0\">\n");
                                        if(isTomcat55) {
                                            out.print("  <GlobalNamingResources>\n"
                                                    + "    <Resource name=\"UserDatabase\" auth=\"Container\"\n"
                                                    + "              type=\"org.apache.catalina.UserDatabase\"\n"
                                                    + "       description=\"User database that can be updated and saved\"\n"
                                                    + "           factory=\"org.apache.catalina.users.MemoryUserDatabaseFactory\"\n"
                                                    + "          pathname=\"conf/tomcat-users.xml\" />\n"
                                                    + "   </GlobalNamingResources>\n");
                                        } else if(tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)) {
                                            out.print("  <Listener className=\"org.apache.catalina.mbeans.ServerLifecycleListener\" debug=\"0\"/>\n"
                                                    + "  <Listener className=\"org.apache.catalina.mbeans.GlobalResourcesLifecycleListener\" debug=\"0\"/>\n"
                                                    + "  <GlobalNamingResources>\n"
                                                    + "    <Resource name=\"UserDatabase\" auth=\"Container\" type=\"org.apache.catalina.UserDatabase\" description=\"User database that can be updated and saved\"/>\n"
                                                    + "    <ResourceParams name=\"UserDatabase\">\n"
                                                    + "      <parameter>\n"
                                                    + "        <name>factory</name>\n"
                                                    + "        <value>org.apache.catalina.users.MemoryUserDatabaseFactory</value>\n"
                                                    + "      </parameter>\n"
                                                    + "      <parameter>\n"
                                                    + "        <name>pathname</name>\n"
                                                    + "        <value>conf/tomcat-users.xml</value>\n"
                                                    + "      </parameter>\n"
                                                    + "    </ResourceParams>\n"
                                                    + "  </GlobalNamingResources>\n");
                                        }
                                        out.print("  <Service name=\"").print(isTomcat55 ? "Catalina" : "Tomcat-Apache").print("\">\n"
                                                + "    <Connector\n"
                                                  /*
                                                + "      className=\"org.apache.coyote.tomcat4.CoyoteConnector\"\n"
                                                + "      port=\"").print(hw.getNetBind().getPort().getPort()).print("\"\n"
                                                + "      minProcessors=\"2\"\n"
                                                + "      maxProcessors=\"200\"\n"
                                                + "      enableLookups=\"true\"\n"
                                                + "      redirectPort=\"443\"\n"
                                                + "      acceptCount=\"10\"\n"
                                                + "      debug=\"0\"\n"
                                                + "      connectionTimeout=\"20000\"\n"
                                                + "      useURIValidationHack=\"false\"\n"
                                                + "      protocolHandlerClassName=\"org.apache.jk.server.JkCoyoteHandler\"\n"
                                                  */);
                                        if (tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)) {
                                            out.print("      className=\"org.apache.ajp.tomcat4.Ajp13Connector\"\n");
                                        }
                                        out.print("      port=\"").print(hw.getNetBind().getPort().getPort()).print("\"\n");
                                        if(isTomcat55) out.print("      enableLookups=\"false\"\n");
                                        out.print("      minProcessors=\"2\"\n"
                                                + "      maxProcessors=\"200\"\n"
                                                + "      address=\""+IPAddress.LOOPBACK_IP+"\"\n"
                                                + "      acceptCount=\"10\"\n"
                                                + "      debug=\"0\"\n"
                                                + "      protocol=\"AJP/1.3\"\n"
                                                + "    />\n"
                                                + "    <Engine name=\"").print(isTomcat55 ? "Catalina" : "Tomcat-Apache").print("\" defaultHost=\"localhost\" debug=\"0\">\n");
                                        if(isTomcat55) {
                                            out.print("      <Realm className=\"org.apache.catalina.realm.UserDatabaseRealm\"\n"
                                                    + "          resourceName=\"UserDatabase\" />\"\n");
                                        } else if(tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)) {
                                            out.print("      <Logger\n"
                                                    + "        className=\"org.apache.catalina.logger.FileLogger\"\n"
                                                    + "        directory=\"var/log\"\n"
                                                    + "        prefix=\"catalina_log.\"\n"
                                                    + "        suffix=\".txt\"\n"
                                                    + "        timestamp=\"true\"\n"
                                                    + "      />\n");
                                            out.print("      <Realm className=\"org.apache.catalina.realm.UserDatabaseRealm\" debug=\"0\" resourceName=\"UserDatabase\" />\n");
                                        } else {
                                            out.print("      <Realm className=\"org.apache.catalina.realm.MemoryRealm\" />\n");
                                        }
                                        out.print("      <Host\n"
                                                + "        name=\"localhost\"\n"
                                                + "        debug=\"0\"\n"
                                                + "        appBase=\"webapps\"\n"
                                                + "        unpackWARs=\"true\"\n");
                                        if(
                                            tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)
                                            || isTomcat55
                                        ) {
                                            out.print("        autoDeploy=\"true\"\n");
                                        }
                                        if(isTomcat55) {
                                            out.print("        xmlValidation=\"false\"\n"
                                                    + "        xmlNamespaceAware=\"false\"\n");
                                        }
                                        out.print("      >\n");
                                        if(tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)) {
                                            out.print("        <Logger\n"
                                                    + "          className=\"org.apache.catalina.logger.FileLogger\"\n"
                                                    + "          directory=\"var/log\"\n"
                                                    + "          prefix=\"localhost_log.\"\n"
                                                    + "          suffix=\".txt\"\n"
                                                    + "          timestamp=\"true\"\n"
                                                    + "        />\n");
                                        }
                                        for(HttpdTomcatContext htc : tomcatSite.getHttpdTomcatContexts()) {
                                            out.print("        <Context\n");
                                            if(htc.getClassName()!=null) out.print("          className=\"").print(htc.getClassName()).print("\"\n");
                                            out.print("          cookies=\"").print(htc.useCookies()).print("\"\n"
                                                    + "          crossContext=\"").print(htc.allowCrossContext()).print("\"\n"
                                                    + "          docBase=\"").print(htc.getDocBase()).print("\"\n"
                                                    + "          override=\"").print(htc.allowOverride()).print("\"\n"
                                                    + "          path=\"").print(htc.getPath()).print("\"\n"
                                                    + "          privileged=\"").print(htc.isPrivileged()).print("\"\n"
                                                    + "          reloadable=\"").print(htc.isReloadable()).print("\"\n"
                                                    + "          useNaming=\"").print(htc.useNaming()).print("\"\n");
                                            if(htc.getWrapperClass()!=null) out.print("          wrapperClass=\"").print(htc.getWrapperClass()).print("\"\n");
                                            out.print("          debug=\"").print(htc.getDebugLevel()).print("\"\n");
                                            if(htc.getWorkDir()!=null) out.print("          workDir=\"").print(htc.getWorkDir()).print("\"\n");
                                            List<HttpdTomcatParameter> parameters=htc.getHttpdTomcatParameters();
                                            List<HttpdTomcatDataSource> dataSources=htc.getHttpdTomcatDataSources();
                                            if(parameters.isEmpty() && dataSources.isEmpty()) {
                                                out.print("        />\n");
                                            } else {
                                                out.print("        >\n");
                                                // Parameters
                                                for(HttpdTomcatParameter parameter : parameters) {
                                                    writeHttpdTomcatParameter(tomcatVersion, parameter, out);
                                                }
                                                // Data Sources
                                                for(HttpdTomcatDataSource dataSource : dataSources) {
                                                    writeHttpdTomcatDataSource(tomcatVersion, dataSource, out);
                                                }
                                                out.print("        </Context>\n");
                                            }
                                        }
                                        out.print("      </Host>\n"
                                                + "    </Engine>\n"
                                                + "  </Service>\n"
                                                + "</Server>\n");
                                    } else throw new SQLException("Unsupported version of Tomcat: "+tomcatVersion);
				} finally {
				    out.flush();
				    out.close();
				}

                                // Flag the JVM as needing a restart if the server.xml file has been modified
                                if(site.getDisableLog()==null) {
                                    boolean needsRestart;
                                    if(confServerXMLFile.exists()) {
                                        needsRestart=!confServerXMLFile.contentEquals(newConfServerXML);
                                    } else needsRestart=true;
                                    if(needsRestart) {
                                        int pkey=site.getPKey();
                                        if(isShared) {
                                            if(!sharedTomcatsNeedingRestarted.contains(pkey)) sharedTomcatsNeedingRestarted.add(shrSite.getHttpdSharedTomcat().getPKey());
                                        } else {
                                            if(!sitesNeedingRestarted.contains(pkey)) sitesNeedingRestarted.add(pkey);
                                        }
                                    }
                                }

                                // Move the file into place
                                newConfServerXML.renameTo(confServerXMLFile);
                            } catch(IOException err) {
                                System.err.println("Error on file: "+newConfServerXML.getFilename());
                                throw err;
                            }
                        } else {
                            try {
                                stripFilePrefix(
                                    confServerXMLFile,
                                    autoWarning
                                );
                            } catch(IOException err) {
                                // Errors OK because this is done in manual mode and they might have symbolic linked stuff
                            }
                        }
                    }
                }
                
                /*
                 * Add the web server config files if they do not exist.
                 */
                // The shared config part
                String sharedConfig=CONF_HOSTS+'/'+siteName;
                hostConfFiles.remove(siteName);
                UnixFile sharedFile=new UnixFile(sharedConfig);
                if(!site.isManual() || !sharedFile.exists()) {
                    String newSharedConfig=sharedConfig+".new";
                    UnixFile newSharedFile=new UnixFile(newSharedConfig);
                    ChainWriter out=new ChainWriter(
                        new BufferedOutputStream(
                            newSharedFile.getSecureOutputStream(
                                UnixFile.ROOT_UID,
                                lsgGID,
                                0640,
                                true
                            )
                        )
                    );
		    try {
			out.print("    ServerAdmin ").print(site.getServerAdmin()).print("\n"
											 + "\n");
			
			if(tomcatSite!=null) {
			    HttpdTomcatVersion htv=tomcatSite.getHttpdTomcatVersion();
			    boolean isTomcat4=htv.isTomcat4(conn);
			    String tomcatVersion=htv.getTechnologyVersion(conn).getVersion();
			    HttpdTomcatStdSite stdSite=tomcatSite.getHttpdTomcatStdSite();
			    HttpdJBossSite jbossSite=tomcatSite.getHttpdJBossSite();
			    HttpdTomcatSharedSite sharedSite=tomcatSite.getHttpdTomcatSharedSite();
			    
			    out.print("    <IfModule !mod_php4.c>\n");
                out.print("    <IfModule !mod_php5.c>\n");
			    boolean useApache=tomcatSite.useApache();
			    if(!useApache) {
				out.print("        <IfModule mod_jk.c>\n"
					  + "            Include conf/options/cgi_php4\n"
					  + "        </IfModule>\n");
			    } else {
				out.print("        Include conf/options/cgi_php4\n");
			    }
			    out.print("    </IfModule>\n");
                out.print("    </IfModule>\n");
			    
			    if(useApache) {
				out.print("    Include conf/options/shtml_standard\n"
					  + "    Include conf/options/mod_rewrite\n"
					  + "\n");
			    }
			    if(stdSite!=null || jbossSite!=null) {
				if(!isTomcat4) {
				    out.print("    <IfModule mod_jserv.c>\n"
                                            + "        ApJServDefaultProtocol ajpv12\n"
                                            + "        ApJServSecretKey DISABLED\n"
                                            + "        ApJServMountCopy on\n"
                                            + "        ApJServLogLevel notice\n"
                                            + "        ApJServMount default /root\n"
                                            + "        AddType text/do .do\n"
                                            + "        AddHandler jserv-servlet .do\n"
                                            + "        AddType text/jsp .jsp\n"
                                            + "        AddHandler jserv-servlet .jsp\n"
                                            + "        AddType text/xml .xml\n"
                                            + "        AddHandler jserv-servlet .xml\n"
                                            + "        <IfModule mod_rewrite.c>\n"
                                            + "            # Support jsessionid\n"
                                            + "            RewriteEngine on\n"
                                            + "            RewriteRule ^(/.*;jsessionid=.*)$ $1 [T=jserv-servlet]\n"
                                            + "        </IfModule>\n"
                                            + "    </IfModule>\n"
                                            + "\n");
				}
			    } else if(sharedSite!=null) {
				out.print("    Include conf/wwwgroup/").print(sharedSite.getHttpdSharedTomcat().getName()).print("\n"
																 + "\n");
			    } else throw new IllegalArgumentException("Unsupported HttpdTomcatSite type: "+site.getPKey());
			    
			    // The CGI user info
			    // Only add this if any of the HttpdServers uses suexec, default to on for safety
			    boolean useSuexec=false;
			    boolean foundOne=false;
			    for(HttpdSiteBind hsb : site.getHttpdSiteBinds()) {
				if(hsb.getHttpdBind().getHttpdServer().useSuexec()) {
				    useSuexec=true;
				    foundOne=true;
				    break;
				}
			    }
			    if(useSuexec || !foundOne) {
				out.print("    User ").print(lsa.getLinuxAccount().getUsername()).print("\n"
                                        + "    Group ").print(lsg.getLinuxGroup().getName()).print("\n"
                                        + "\n");
			    }

                            // Write the standard restricted URL patterns
                            out.print("    # Standard protected URLs\n");
                            for(int d=0;d<standardProtectedURLs.length;d++) {
                                out.print("    <LocationMatch \"").print(standardProtectedURLs[d]).print("\">\n"
                                        + "\tAllowOverride None\n"
                                        + "\tDeny from All\n"
                                        + "    </LocationMatch>\n"
                                );
                            }
                            out.print("    # Protect automatic backups\n"
                                    + "    <IfModule mod_rewrite.c>\n"
                                    + "        RewriteEngine on\n"
                                    + "\n"
                                    + "        # Redirect past automatic backups\n"
                                    + "        RewriteRule ^(.*).do~$ $1.do [L,R]\n"
                                    + "        RewriteRule ^(.*).jsp~$ $1.jsp [L,R]\n"
                                    + "        RewriteRule ^(.*).jspa~$ $1.jspa [L,R]\n"
                                    + "        RewriteRule ^(.*).php~$ $1.php [L,R]\n"
                                    + "        RewriteRule ^(.*).shtml~$ $1.shtml [L,R]\n"
                                    + "        RewriteRule ^(.*).vm~$ $1.vm [L,R]\n"
                                    + "        RewriteRule ^(.*).xml~$ $1.xml [L,R]\n"
                                    + "\n"
                                    + "        # Protect dangerous request methods\n"
                                    + "        RewriteCond %{REQUEST_METHOD} ^(TRACE|TRACK)\n"
                                    + "        RewriteRule .* - [F]\n"
                                    + "    </IfModule>\n");

                            // Set up all of the webapps
			    for(HttpdTomcatContext htc : tomcatSite.getHttpdTomcatContexts()) {
				String path=htc.getPath();
				String docBase=htc.getDocBase();
				if(path.length()==0) {
                                    out.print("    # Set up the default webapp\n"
                                            + "    DocumentRoot ").print(docBase).print("\n"
                                            + "    <Directory ").print(docBase).print(">\n"
                                            + "        Allow from All\n"
                                            + "        AllowOverride All\n"
                                            + "        Order allow,deny\n"
                                            + "        Options Indexes IncludesNOEXEC\n"
                                            + "    </Directory>\n");
				} else if(useApache) {
                                    out.print("    # Set up the ").print(path).print(" webapp\n"
                                            + "    AliasMatch ^").print(path).print("$ ").print(docBase).print("\n"
                                            + "    AliasMatch ^").print(path).print("/(.*) ").print(docBase).print("/$1\n"
                                            + "    <Directory \"").print(docBase).print("\">\n"
                                            + "        Allow from All\n"
                                            + "        AllowOverride All\n"
                                            + "        Order allow,deny\n"
                                            + "        Options Indexes IncludesNOEXEC\n"
                                            + "    </Directory>\n");
				}
				if(useApache) {
				    out.print("    ScriptAlias ").print(path).print("/cgi-bin/ ").print(docBase).print("/cgi-bin/\n"
                                            + "    <Directory ").print(docBase).print("/cgi-bin>\n"
                                            + "        Options ExecCGI\n"
                                            + "        <IfModule mod_ssl.c>\n"
                                            + "            SSLOptions +StdEnvVars\n"
                                            + "        </IfModule>\n"
                                            + "        Allow from All\n"
                                            + "        Order allow,deny\n"
                                            + "    </Directory>\n");
				} else {
				    out.print("    <IfModule mod_jk.c>\n"
                                            + "        ScriptAlias ").print(path).print("/cgi-bin/ ").print(docBase).print("/cgi-bin/\n"
                                            + "        <Directory ").print(docBase).print("/cgi-bin>\n"
                                            + "            Options ExecCGI\n"
                                            + "            <IfModule mod_ssl.c>\n"
                                            + "                SSLOptions +StdEnvVars\n"
                                            + "            </IfModule>\n"
                                            + "            Allow from All\n"
                                            + "            Order allow,deny\n"
                                            + "        </Directory>\n"
                                            + "    </IfModule>\n");
				}
				if(useApache) {
				    out.print("    <Location ").print(path).print("/META-INF/>\n"
										  + "        AllowOverride None\n"
										  + "        Deny from All\n"
										  + "    </Location>\n"
										  + "    <Location ").print(path).print("/WEB-INF/>\n"
															+ "        AllowOverride None\n"
															+ "        Deny from All\n"
															+ "    </Location>\n");
				}
				out.print('\n');
			    }
			    
			    List<HttpdWorker> workers=tomcatSite.getHttpdWorkers();
			    String ajp12Code=null;
			    if(!isTomcat4) {
				// Look for the first AJP12 worker
				int ajp12Port=-1;
				for(HttpdWorker hw : workers) {
				    if(hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol().equals(HttpdJKProtocol.AJP12)) {
					ajp12Port=hw.getNetBind().getPort().getPort();
					ajp12Code=hw.getCode().getCode();
					break;
				    }
				}
				if(ajp12Port!=-1) {
				    out.print("    <IfModule mod_jserv.c>\n"
					      + "        ApJServDefaultPort ").print(ajp12Port).print("\n");
				    for(HttpdTomcatContext htc : tomcatSite.getHttpdTomcatContexts()) {
					String path=htc.getPath();
					String relPath=useApache?path+"/servlet":path;
					out.print("        ApJServMount ").print(relPath.length()==0?"/":relPath).print(' ').print(path.length()==0?"/"+HttpdTomcatContext.ROOT_DOC_BASE:path).print("\n");
				    }
				    out.print("    </IfModule>\n"
					      + "\n");
				}
			    }
			    
			    int ajp13Port=-1;
			    String ajp13Code=null;
			    if(isTomcat4 && sharedSite!=null) {
				HttpdWorker hw=sharedSite.getHttpdSharedTomcat().getTomcat4Worker();
				ajp13Port=hw.getNetBind().getPort().getPort();
				ajp13Code=hw.getCode().getCode();
			    } else {
				for(HttpdWorker hw : workers) {
				    if(hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol().equals(HttpdJKProtocol.AJP13)) {
					ajp13Port=hw.getNetBind().getPort().getPort();
					ajp13Code=hw.getCode().getCode();
					break;
				    }
				}
			    }
			    String jkCode=ajp13Code!=null?ajp13Code:ajp12Code;
			    out.print("    <IfModule mod_jk.c>\n"
                                    + "        # Redirect past automatic backups\n"
                                    + "        <IfModule mod_rewrite.c>\n"
                                    + "            RewriteEngine on\n"
                                    + "            RewriteRule ^(.*).do~$ $1.do [L,R]\n"
                                    + "            RewriteRule ^(.*).jsp~$ $1.jsp [L,R]\n"
                                    + "            RewriteRule ^(.*).jspa~$ $1.jspa [L,R]\n"
                                    + "            RewriteRule ^(.*).vm~$ $1.vm [L,R]\n"
                                    + "            RewriteRule ^(.*).xml~$ $1.xml [L,R]\n"
                                    + "        </IfModule>\n");
			    if(useApache) {
				for(HttpdTomcatContext context : tomcatSite.getHttpdTomcatContexts()) {
				    String path=context.getPath();
                                    out.print("        JkMount ").print(path).print("/j_security_check ").print(jkCode).print("\n"
                                    + "        JkMount ").print(path).print("/servlet/* ").print(jkCode).print("\n"
                                    + "        JkMount ").print(path).print("/*.do ").print(jkCode).print("\n"
                                    + "        JkMount ").print(path).print("/*.jsp ").print(jkCode).print("\n"
                                    + "        JkMount ").print(path).print("/*.jspa ").print(jkCode).print("\n"
                                    + "        JkMount ").print(path).print("/*.vm ").print(jkCode).print("\n"
                                    + "        JkMount ").print(path).print("/*.xml ").print(jkCode).print("\n");
				}
			    } else {
				out.print("        JkMount /* ").print(jkCode).print("\n");
			    }
                out.print("        # Remove jsessionid for non-jk requests\n"
                        + "        <IfModule mod_rewrite.c>\n"
                        + "            RewriteEngine On\n"
                        + "            RewriteRule ^(.*);jsessionid=.*$ $1\n"
                        + "        </IfModule>\n"
			            + "    </IfModule>\n"
                        + "\n");
                /*
			    if(useApache) {
                                String analogImages;
                                if(osv==OperatingSystemVersion.REDHAT_7_2_I686) analogImages="/var/www/html/images";
                                else if(
                                    osv==OperatingSystemVersion.MANDRAKE_9_2_I586
                                    || osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                                ) analogImages="/usr/analog/5.32/images";
                                else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
				out.print("    # The analog config\n"
					  + "    AliasMatch ^/analog/images$ ").print(analogImages).print("\n"
                                          + "    AliasMatch ^/analog/images/(.*) ").print(analogImages).print("/$1\n"
					  + "    <Directory ").print(analogImages).print(">\n"
					  + "        Allow from All\n"
					  + "        Order allow,deny\n"
					  + "        Options Indexes\n"
					  + "    </Directory>\n"
                                  );
			    }
                 */
			} else throw new IllegalArgumentException("Unsupported HttpdSite type for HttpdSite #"+site.getPKey());
		    } finally {
			out.flush();
			out.close();
		    }
                    
                    if(!sharedFile.exists() || !sharedFile.contentEquals(newSharedFile)) {
                        for(HttpdSiteBind hsb : site.getHttpdSiteBinds()) {
                            int hsPKey=hsb.getHttpdBind().getHttpdServer().getPKey();
                            if(!serversNeedingRestarted.contains(hsPKey)) serversNeedingRestarted.add(hsPKey);
                        }
                        newSharedFile.renameTo(sharedFile);
                    } else newSharedFile.delete();
                }
                // Each of the binds
                for(HttpdSiteBind bind : binds) {
                    NetBind netBind=bind.getHttpdBind().getNetBind();
                    String ipAddress=netBind.getIPAddress().getIPAddress();
                    int port=netBind.getPort().getPort();
                    String bindConfig=CONF_HOSTS+'/'+siteName+'_'+ipAddress+'_'+port;
                    hostConfFiles.remove(siteName+'_'+ipAddress+'_'+port);
                    UnixFile bindFile=new UnixFile(bindConfig);
                    if(!bind.isManual() || !bindFile.exists()) {
                        String newBindConfig=bindConfig+".new";
                        UnixFile newBindFile=new UnixFile(newBindConfig);
                        writeHttpdSiteBindFile(bind, newBindFile, bind.getDisableLog()!=null?HttpdSite.DISABLED:siteName);
                        if(!bindFile.exists() || !bindFile.contentEquals(newBindFile)) {
                            int hsPKey=bind.getHttpdBind().getHttpdServer().getPKey();
                            if(!serversNeedingRestarted.contains(hsPKey)) serversNeedingRestarted.add(hsPKey);
                            newBindFile.renameTo(bindFile);
                        } else newBindFile.delete();
                    }
                }

                /*
                 * Make the private FTP space, if needed.
                 */
                String ftpPath=FTPManager.SHARED_FTP_DIRECTORY+'/'+siteName;
                ftpDirectories.remove(siteName);
                UnixFile ftpDir=new UnixFile(ftpPath);
                if(!ftpDir.exists()) ftpDir.mkdir().chown(lsaUID, lsgGID).setMode(0775);
            }

            /*
             * Delete any extra files once the rebuild is done
             */
            for(int c=0;c<hostConfFiles.size();c++) deleteFileList.add(new File(CONF_HOSTS, hostConfFiles.get(c)));
            for(int c=0;c<logDirectories.size();c++) deleteFileList.add(new File(LOG_DIR, logDirectories.get(c)));
            for(int c=0;c<wwwDirectories.size();c++) {
                String siteName=wwwDirectories.get(c);
                stopHttpdSite(siteName);
                disableHttpdSite(siteName);
                String fullPath=HttpdSite.WWW_DIRECTORY+'/'+siteName;
                if(!aoServer.isHomeUsed(fullPath)) {
                    deleteFileList.add(new File(fullPath));
                }
            }
            for(int c=0;c<ftpDirectories.size();c++) deleteFileList.add(new File(FTPManager.SHARED_FTP_DIRECTORY, ftpDirectories.get(c)));
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void writeHttpdSiteBindFile(HttpdSiteBind bind, UnixFile file, String siteInclude) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "writeHttpdSiteBindFile(HttpdSiteBind,UnixFile,String)", null);
        try {
            NetBind netBind=bind.getHttpdBind().getNetBind();
            int port=netBind.getPort().getPort();
            int lsgGID=bind.getHttpdSite().getLinuxServerGroup().getGID().getID();

            ChainWriter out=new ChainWriter(
                new BufferedOutputStream(
                    file.getSecureOutputStream(UnixFile.ROOT_UID, lsgGID, 0640, true)
                )
            );
	    try {
            out.print("<VirtualHost ").print(netBind.getIPAddress().getIPAddress()).print(':').print(port).print(">\n"
                    + "    ServerName ").print(bind.getPrimaryHttpdSiteURL().getHostname()).print('\n'
            );
            List<HttpdSiteURL> altURLs=bind.getAltHttpdSiteURLs();
            if(!altURLs.isEmpty()) {
                out.print("    ServerAlias");
                for(HttpdSiteURL altURL : altURLs) {
                    out.print(' ').print(altURL.getHostname());
                }
                out.print('\n');
            }
            out.print("\n"
                    + "    CustomLog ").print(bind.getAccessLog()).print(" common\n"
                    + "    ErrorLog ").print(bind.getErrorLog()).print("\n"
                    + "\n");
            String sslCert=bind.getSSLCertFile();
            if(sslCert!=null) {
                out.print("    <IfModule mod_ssl.c>\n"
                        + "        SSLCertificateFile ").print(sslCert).print("\n"
                        + "        SSLCertificateKeyFile ").print(bind.getSSLCertKeyFile()).print("\n"
                        + "        SSLCACertificateFile /etc/ssl/CA/ca.txt\n"
                        + "        <Files ~ \"\\.(.cgi|shtml|phtml|php3?)$\">\n"
                        + "            SSLOptions +StdEnvVars\n"
                        + "        </Files>\n"
                        + "        SSLEngine On\n"
                        + "    </IfModule>\n"
                        + "\n"
                );
            }
            out.print("    Include conf/hosts/").print(siteInclude).print("\n"
                    + "\n"
                    + "</VirtualHost>\n");
	    } finally {
		out.flush();
		out.close();
	    }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Only called by the internally synchronized <code>doRebuild()</code> method.
     */
    private static void doRebuildSharedTomcats(
	AOServer aoServer,
	List<File> deleteFileList,
	AOServConnector conn,
        IntList sharedTomcatsNeedingRestarted
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "doRebuildSharedTomcats(AOServer,List<File>,AOServConnector,IntList)", null);
        try {
            int osv=aoServer.getServer().getOperatingSystemVersion().getPKey();

            // get shared tomcats
            List<HttpdSharedTomcat> tomcats = aoServer.getHttpdSharedTomcats();

            // The www group directories that exist but are not used will be removed
            String[] list = new File(HttpdSharedTomcat.WWW_GROUP_DIR).list();
            List<String> wwwGroupDirectories = new SortedArrayList<String>(list.length);
            for (int c = 0; c < list.length; c++) {
                String dirname = list[c];
                if(
                    !dirname.equals("lost+found")
                    && !dirname.equals(".backup")
                )
                wwwGroupDirectories.add(dirname);
            }

            // The config directory should only contain files referenced in the database
            list = new File(CONF_WWWGROUPS).list();
            List<String> groupConfFiles = new SortedArrayList<String>(list.length);
            for (int c = 0; c < list.length; c++) groupConfFiles.add(list[c]);

            int len = tomcats.size();
            for (int i = 0; i < len; i++) {
                /*
                 * Get values used in the rest of the loop.
                 */
                final HttpdSharedTomcat tomcat = tomcats.get(i);
                final HttpdTomcatVersion htv=tomcat.getHttpdTomcatVersion();
                final boolean isTomcat4=htv.isTomcat4(conn);
                final boolean isTomcat55=htv.isTomcat55(conn);
                final String tomcatDirectory=htv.getInstallDirectory();
                String version = htv.getTechnologyVersion(conn).getVersion();
                
                final LinuxServerAccount lsa = tomcat.getLinuxServerAccount();
                final int lsaUID = lsa.getUID().getID();

                final LinuxServerGroup lsg = tomcat.getLinuxServerGroup();
                final int lsgGID = lsg.getGID().getID();

                /*
                 * Create and fill in the directory if it does not exist or is owned by root.
                 */
                final UnixFile wwwGroupDirUF = new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR, tomcat.getName());
                UnixFile workUF = new UnixFile(wwwGroupDirUF, "work");
                UnixFile innerWorkUF;
                if(isTomcat4) {
                    innerWorkUF=new UnixFile(workUF, "Tomcat-Apache");
                } else {
                    innerWorkUF=null;
                }

                boolean needRestart=false;
                if (!wwwGroupDirUF.exists() || wwwGroupDirUF.getUID() == UnixFile.ROOT_GID) {

                    final String wwwGroupDir = wwwGroupDirUF.getFilename();
                    final int mgroup = (tomcat.isSecure() ? 0750 : 0770);
                    final int mprivate = (tomcat.isSecure() ? 0700 : 0770);

                    // Create the /wwwgroup/name/...

                    // 001
                    if (!wwwGroupDirUF.exists()) wwwGroupDirUF.mkdir();
                    wwwGroupDirUF.setMode(tomcat.isSecure() ? 0750 : 0770);
                    new UnixFile(wwwGroupDirUF, "bin").mkdir().chown(lsaUID, lsgGID).setMode(mgroup);
                    new UnixFile(wwwGroupDirUF, "conf").mkdir().chown(lsaUID, lsgGID).setMode(tomcat.isSecure() ? 0750 : 0770);
                    UnixFile daemonUF = new UnixFile(wwwGroupDirUF, "daemon").mkdir().chown(lsaUID, lsgGID).setMode(mprivate);
                    if(tomcat.getDisableLog()==null) new UnixFile(daemonUF, "tomcat").symLink("../bin/tomcat").chown(lsaUID, lsgGID);
                    if(isTomcat4) {
                        ln("var/log", wwwGroupDir+"/logs", lsaUID, lsgGID);
                        mkdir(wwwGroupDir+"/temp", mprivate, lsa, lsg);
                    }
                    UnixFile varUF = new UnixFile(wwwGroupDirUF, "var").mkdir().chown(lsaUID, lsgGID).setMode(isTomcat4?mgroup:mprivate);
                    new UnixFile(varUF, "log").mkdir().chown(lsaUID, lsgGID).setMode(isTomcat4?mgroup:mprivate);
                    new UnixFile(varUF, "run").mkdir().chown(lsaUID, lsgGID).setMode(0700);
                    
                    workUF.mkdir().chown(lsaUID, lsgGID).setMode(0750);
                    if(innerWorkUF!=null) {
                        mkdir(innerWorkUF.getFilename(), 0750, lsa, lsg);
                    }

                    PostgresServer postgresServer=aoServer.getPreferredPostgresServer();
                    String postgresServerMinorVersion=postgresServer==null?null:postgresServer.getPostgresVersion().getMinorVersion();

                    if(isTomcat4) {
                        ln("../../.."+tomcatDirectory+"/bin/bootstrap.jar", wwwGroupDir+"/bin/bootstrap.jar", lsaUID, lsgGID);
                        ln("../../.."+tomcatDirectory+"/bin/catalina.sh", wwwGroupDir+"/bin/catalina.sh", lsaUID, lsgGID);
                        //UnixFile catalinaUF=new UnixFile(wwwGroupDir+"/bin/catalina.sh");
                        //new UnixFile(tomcatDirectory+"/bin/catalina.sh").copyTo(catalinaUF, false);
                        //catalinaUF.chown(lsaUID, lsgGID).setMode(tomcat.isSecure()?0700:0740);
                        if(
                            version.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)
                            || isTomcat55
                        ) {
                            ln("../../.."+tomcatDirectory+"/bin/commons-daemon.jar", wwwGroupDir+"/bin/commons-daemon.jar", lsaUID, lsgGID);
                            if(isTomcat55) ln("../../.."+tomcatDirectory+"/bin/commons-logging-api.jar", wwwGroupDir+"/bin/commons-logging-api.jar", lsaUID, lsgGID);
                            if(!isTomcat55) ln("../../.."+tomcatDirectory+"/bin/jasper.sh", wwwGroupDir+"/bin/jasper.sh", lsaUID, lsgGID);
                            if(!isTomcat55) ln("../../.."+tomcatDirectory+"/bin/jspc.sh", wwwGroupDir+"/bin/jspc.sh", lsaUID, lsgGID);
                        }
                        ln("../../.."+tomcatDirectory+"/bin/digest.sh", wwwGroupDir+"/bin/digest.sh", lsaUID, lsgGID);
                    }

                    String profileFile = wwwGroupDir + "/bin/profile";
                    LinuxAccountManager.setBashProfile(lsa, profileFile);

                    UnixFile profileUF = new UnixFile(profileFile);
                    ChainWriter out = new ChainWriter(
                        new BufferedOutputStream(
                            profileUF.getSecureOutputStream(lsaUID, lsgGID, 0750, false)
                        )
                    );
		    try {
			out.print("#!/bin/sh\n"
				  + "\n");

			if(isTomcat4) {
			    out.print(". /etc/profile\n");
                            if (version.startsWith(HttpdTomcatVersion.VERSION_5_5_PREFIX)) {
                                out.print(". /usr/aoserv/etc/").print(DEFAULT_TOMCAT_5_JDK_VERSION).print(".sh\n");
                            } else {
                                out.print(". /usr/aoserv/etc/").print(getDefaultJdkVersion(osv)).print(".sh\n");
                            }
                            out.print(". /usr/aoserv/etc/php-").print(getDefaultPhpVersion(osv)).print(".sh\n");
			    if(postgresServerMinorVersion!=null) out.print(". /usr/aoserv/etc/postgresql-").print(postgresServerMinorVersion).print(".sh\n");
			    out.print(". /usr/aoserv/etc/aoserv.sh\n"
				      + "\n"
				      + "umask 002\n"
				      + "export DISPLAY=:0.0\n"
				      + "\n"
				      + "export CATALINA_BASE=\"").print(wwwGroupDir).print("\"\n"
                                      + "export CATALINA_HOME=\"").print(wwwGroupDir).print("\"\n"
                                      + "export CATALINA_TEMP=\"").print(wwwGroupDir).print("/temp\"\n"
																		  );
			} else if (version.startsWith(HttpdTomcatVersion.VERSION_3_2_PREFIX) || version.startsWith(HttpdTomcatVersion.VERSION_3_1_PREFIX)) {
                            out.print(". /etc/profile\n");
                            if(tomcat.isSecure()) {
                                out.print(". /usr/aoserv/etc/jdk1.3.1_03.sh\n");
                            } else {
                                out.print(". /usr/aoserv/etc/").print(getDefaultJdkVersion(osv)).print(".sh\n");
                            }
                            out.print(". /usr/aoserv/etc/jakarta-oro-2.0.1.sh\n"
                                    + ". /usr/aoserv/etc/jakarta-regexp-1.2.sh\n"
                                    + ". /usr/aoserv/etc/jakarta-servletapi-3.2.sh\n"
                                    + ". /usr/aoserv/etc/jakarta-tomcat-3.2.sh\n"
                                    + ". /usr/aoserv/etc/jetspeed-1.1.sh\n"
                                    + ". /usr/aoserv/etc/cocoon-1.8.2.sh\n"
                                    + ". /usr/aoserv/etc/xerces-1.2.0.sh\n"
                                    + ". /usr/aoserv/etc/ant-1.6.2.sh\n"
                                    + ". /usr/aoserv/etc/xalan-1.2.d02.sh\n"
                                    + ". /usr/aoserv/etc/php-").print(getDefaultPhpVersion(osv)).print(".sh\n");
                            if(postgresServerMinorVersion!=null) out.print(". /usr/aoserv/etc/postgresql-").print(postgresServerMinorVersion).print(".sh\n");
                            out.print(". /usr/aoserv/etc/profile\n"
                                    + ". /usr/aoserv/etc/fop-0.15.0.sh\n");
                        } else throw new IllegalArgumentException("Unknown version of Tomcat: " + version);

                        out.print("\n"
                                //+ "if [ -d ").print(wwwGroupDir).print("/httpd/bin ] ; then\n"
                                //+ "    export PATH=").print(wwwGroupDir).print("/httpd/bin:${PATH}\n"
                                //+ "fi\n"
                                //+ "\n"
                                + "export PATH=${PATH}:").print(wwwGroupDir).print("/bin\n"
                                + "\n");
                        if(!isTomcat4) {
                            out.print("# Add site group classes\n"
                                    + "CLASSPATH=${CLASSPATH}:").print(wwwGroupDir).print("/classes\n"
                                    + "for i in ").print(wwwGroupDir).print("/lib/* ; do\n"
                                    + "    if [ -f $i ]; then\n"
                                    + "        CLASSPATH=${CLASSPATH}:$i\n"
                                    + "    fi\n"
                                    + "done\n"
                                    + "\n");
                        }
                        out.print(". "+HttpdSharedTomcat.WWW_GROUP_DIR+'/').print(tomcat.getName()).print("/bin/profile.sites\n"
                                + "\n"
                                + "for SITE in $SITES ; do\n"
                                + "    export PATH=${PATH}:"+HttpdSite.WWW_DIRECTORY+"/${SITE}/bin\n");
                        if(!isTomcat4) {
                            out.print("    CLASSPATH=${CLASSPATH}:"+HttpdSite.WWW_DIRECTORY+"/${SITE}/classes\n"
                                    + "\n"
                                    + "    for i in "+HttpdSite.WWW_DIRECTORY+"/${SITE}/lib/* ; do\n"
                                    + "        if [ -f $i ]; then\n"
                                    + "            CLASSPATH=${CLASSPATH}:$i\n"
                                    + "        fi\n"
                                    + "    done\n");
                        }
                        out.print("done\n"
                                + "export CLASSPATH\n");
		    } finally {
			out.flush();
			out.close();
		    }

                    // 004

                    UnixFile tomcatUF = new UnixFile(wwwGroupDir + "/bin/tomcat");
                    out=new ChainWriter(
                        new BufferedOutputStream(
                            tomcatUF.getSecureOutputStream(lsaUID, lsgGID, 0700, false)
                        )
                    );
		    try {
                    out.print("#!/bin/sh\n"
                            + "\n"
                            + "TOMCAT_HOME=").print(wwwGroupDir).print("\n"
                            + "\n"
                            + "if [ \"$1\" = \"start\" ]; then\n"
                            + "    $0 stop\n"
                            + "    $0 daemon &\n"
                            + "    echo $! >${TOMCAT_HOME}/var/run/tomcat.pid\n"
                            + "elif [ \"$1\" = \"stop\" ]; then\n"
                            + "    if [ -f ${TOMCAT_HOME}/var/run/tomcat.pid ]; then\n"
                            + "        kill `cat ${TOMCAT_HOME}/var/run/tomcat.pid`\n"
                            + "        rm -f ${TOMCAT_HOME}/var/run/tomcat.pid\n"
                            + "    fi\n"
                            + "    if [ -f ${TOMCAT_HOME}/var/run/java.pid ]; then\n"
                            + "        . $TOMCAT_HOME/bin/profile\n"
                            + "        if [ \"$SITES\" != \"\" ]; then\n"
                            + "            cd $TOMCAT_HOME\n"
                    );
                    if(isTomcat4) {
                        out.print("            ${TOMCAT_HOME}/bin/catalina.sh stop 2>&1 >>${TOMCAT_HOME}/var/log/tomcat_err\n");
                    } else if(tomcat.isSecure()) {
                        out.print("            java com.aoindustries.apache.tomcat.SecureVirtualTomcat stop $SITES &>/dev/null\n");
                    } else out.print("            java com.aoindustries.apache.tomcat.VirtualTomcat stop $SITES &>/dev/null\n");
                    out.print("        fi\n"
                            + "        kill `cat ${TOMCAT_HOME}/var/run/java.pid` &>/dev/null\n"
                            + "        rm -f ${TOMCAT_HOME}/var/run/java.pid\n"
                            + "    fi\n"
                            + "elif [ \"$1\" = \"daemon\" ]; then\n"
                            + "    cd $TOMCAT_HOME\n"
                            + "    . $TOMCAT_HOME/bin/profile\n"
                            + "\n"
                            + "    # Get rid of sites without servlet container content\n"
                            + "    SITES=`/usr/aoserv/sbin/filtersites $SITES`\n"
                            + "\n"
                            + "    if [ \"$SITES\" != \"\" ]; then\n"
                            + "        while [ 1 ]; do\n"
                    );
                    if(isTomcat4) {
                        if(tomcat.isSecure()) {
                            out.print("            ${TOMCAT_HOME}/bin/build_catalina_policy >${TOMCAT_HOME}/conf/catalina.policy\n"
                                    + "            chmod 600 ${TOMCAT_HOME}/conf/catalina.policy\n"
                                    + "            mv -f ${TOMCAT_HOME}/var/log/tomcat_err ${TOMCAT_HOME}/var/log/tomcat_err.old\n"
                                    + "            ${TOMCAT_HOME}/bin/catalina.sh run -security >&${TOMCAT_HOME}/var/log/tomcat_err &\n");
                        } else {
                            out.print("            mv -f ${TOMCAT_HOME}/var/log/tomcat_err ${TOMCAT_HOME}/var/log/tomcat_err.old\n"
                                    + "            ${TOMCAT_HOME}/bin/catalina.sh run >&${TOMCAT_HOME}/var/log/tomcat_err &\n");
                        }
                    } else {
                        if(tomcat.isSecure()) out.print("            java com.aoindustries.apache.tomcat.SecureVirtualTomcat start $SITES &>var/log/servlet_err &\n");
                        else out.print("            java com.aoindustries.apache.tomcat.VirtualTomcat start $SITES &>var/log/servlet_err &\n");
                    }
                    out.print("            echo $! >var/run/java.pid\n"
                            + "            wait\n"
                            + "            RETCODE=$?\n"
                            + "            echo \"`date`: JVM died with a return code of $RETCODE, restarting in 5 seconds\" >>${TOMCAT_HOME}/var/log/jvm_crashes.log\n"
                            + "            sleep 5\n"
                            + "        done\n"
                            + "    fi\n"
                            + "    rm -f ${TOMCAT_HOME}/var/run/tomcat.pid\n"
                            + "else\n"
                            + "    echo \"Usage:\"\n"
                            + "    echo \"tomcat {start|stop}\"\n"
                            + "    echo \"        start - start tomcat\"\n"
                            + "    echo \"        stop  - stop tomcat\"\n"
                            + "fi\n"
                    );
		    } finally {
			out.flush();
			out.close();
		    }

                    if(isTomcat4) {
                        ln("../../.."+tomcatDirectory+"/bin/setclasspath.sh", wwwGroupDir+"/bin/setclasspath.sh", lsaUID, lsgGID);

                        UnixFile shutdown=new UnixFile(wwwGroupDir+"/bin/shutdown.sh");
                        out=new ChainWriter(shutdown.getSecureOutputStream(lsaUID, lsgGID, 0700, true));
			try {
			    out.print("#!/bin/sh\n"
				      + "exec ").print(wwwGroupDir).print("/bin/tomcat stop\n");
			} finally {
			    out.flush();
			    out.close();
			}

                        UnixFile startup=new UnixFile(wwwGroupDir+"/bin/startup.sh");
                        out=new ChainWriter(startup.getSecureOutputStream(lsaUID, lsgGID, 0700, true));
			try {
			    out.print("#!/bin/sh\n"
				      + "exec ").print(wwwGroupDir).print("/bin/tomcat start\n");
			} finally {
			    out.flush();
			    out.close();
			}

                        if(
                            version.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)
                            || isTomcat55
                        ) {
                            if(!isTomcat55) ln("../../.."+tomcatDirectory+"/bin/tomcat-jni.jar", wwwGroupDir+"/bin/tomcat-jni.jar", lsaUID, lsgGID);
                            if(isTomcat55) ln("../../.."+tomcatDirectory+"/bin/tomcat-juli.jar", wwwGroupDir+"/bin/tomcat-juli.jar", lsaUID, lsgGID);
                            ln("../../.."+tomcatDirectory+"/bin/tool-wrapper.sh", wwwGroupDir+"/bin/tool-wrapper.sh", lsaUID, lsgGID);
                            if(isTomcat55) ln("../../.."+tomcatDirectory+"/bin/version.sh", wwwGroupDir+"/bin/version.sh", lsaUID, lsgGID);
                        }

                        /*
                         * The classes directory
                         */
                        if(
                            !version.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)
                            && !isTomcat55
                        ) new UnixFile(wwwGroupDirUF, "classes").mkdir().chown(lsaUID, lsgGID).setMode(mgroup);

                        /*
                         * Create the common directory and all contents
                         */
                        mkdir(wwwGroupDir+"/common", tomcat.isSecure()?0750:0770, lsa, lsg);
                        mkdir(wwwGroupDir+"/common/classes", tomcat.isSecure()?0750:0770, lsa, lsg);
                        mkdir(wwwGroupDir+"/common/endorsed", tomcat.isSecure()?0750:0770, lsa, lsg);
                        lnAll("../../../.."+tomcatDirectory+"/common/endorsed/", wwwGroupDir+"/common/endorsed/", lsaUID, lsgGID);
                        mkdir(wwwGroupDir+"/common/i18n", tomcat.isSecure()?0750:0770, lsa, lsg);
                        if(isTomcat55) lnAll("../../../.."+tomcatDirectory+"/common/i18n/", wwwGroupDir+"/common/i18n/", lsaUID, lsgGID);
                        mkdir(wwwGroupDir+"/common/lib", tomcat.isSecure()?0750:0770, lsa, lsg);
                        lnAll("../../../.."+tomcatDirectory+"/common/lib/", wwwGroupDir+"/common/lib/", lsaUID, lsgGID);
                        
                        if(postgresServerMinorVersion!=null) ln("../../../../usr/postgresql/"+postgresServerMinorVersion+"/share/java/postgresql.jar", wwwGroupDir+"/common/lib/postgresql.jar", lsaUID, lsgGID);
                        if(osv==OperatingSystemVersion.MANDRAKE_10_1_I586) ln("../../../../usr/mysql-connector-java/3.1.12/mysql-connector-java-3.1.12-bin.jar", wwwGroupDir+"/common/lib/mysql-connector-java-3.1.12-bin.jar", lsaUID, lsgGID);
                        else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

                        /*
                         * Write the conf/catalina.policy file
                         */
                        if(!tomcat.isSecure()) {
                            {
                                UnixFile cp=new UnixFile(wwwGroupDir+"/conf/catalina.policy");
                                new UnixFile(tomcatDirectory+"/conf/catalina.policy").copyTo(cp, false);
                                cp.chown(lsaUID, lsgGID).setMode(0660);
                            }
                            if(isTomcat55) {
                                UnixFile cp=new UnixFile(wwwGroupDir+"/conf/catalina.properties");
                                new UnixFile(tomcatDirectory+"/conf/catalina.properties").copyTo(cp, false);
                                cp.chown(lsaUID, lsgGID).setMode(0660);
                            }
                            if(isTomcat55) {
                                UnixFile cp=new UnixFile(wwwGroupDir+"/conf/context.xml");
                                new UnixFile(tomcatDirectory+"/conf/context.xml").copyTo(cp, false);
                                cp.chown(lsaUID, lsgGID).setMode(0660);
                            }
                            if(isTomcat55) {
                                UnixFile cp=new UnixFile(wwwGroupDir+"/conf/logging.properties");
                                new UnixFile(tomcatDirectory+"/conf/logging.properties").copyTo(cp, false);
                                cp.chown(lsaUID, lsgGID).setMode(0660);
                            }
                        }

                        /*
                         * Create the tomcat-users.xml file
                         */
                        UnixFile tuUF=new UnixFile(wwwGroupDir+"/conf/tomcat-users.xml");
                        new UnixFile(tomcatDirectory+"/conf/tomcat-users.xml").copyTo(tuUF, false);
                        tuUF.chown(lsaUID, lsgGID).setMode(tomcat.isSecure()?0640:0660);

                        /*
                         * Create the web.xml file.
                         */
                        UnixFile webUF=new UnixFile(wwwGroupDir+"/conf/web.xml");
                        new UnixFile(tomcatDirectory+"/conf/web.xml").copyTo(webUF, false);
                        webUF.chown(lsaUID, lsgGID).setMode(tomcat.isSecure()?0640:0660);

                        /*
                         * Create /lib
                         */
                        if(!isTomcat4) {
                            new UnixFile(wwwGroupDirUF, "lib").mkdir().chown(lsaUID, lsgGID).setMode(mgroup);
                            lnAll("../../.."+tomcatDirectory+"/lib/", wwwGroupDir+"/lib/", lsaUID, lsgGID);
                            ln("../../.."+tomcatDirectory+"/lib/jasper-runtime.jar", wwwGroupDir+"/lib/jasper-runtime.jar", lsaUID, lsgGID);
                            if(postgresServerMinorVersion!=null) ln("../../../usr/postgresql/"+postgresServerMinorVersion+"/share/java/postgresql.jar", wwwGroupDir+"/lib/postgresql.jar", lsaUID, lsgGID);
                            if(
                                osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                            ) ln("../../../usr/mysql-connector-java/3.1.12/mysql-connector-java-3.1.12-bin.jar", wwwGroupDir+"/lib/mysql-connector-java-3.1.12-bin.jar", lsaUID, lsgGID);
                            else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                        }
                        mkdir(wwwGroupDir+"/server", tomcat.isSecure()?0750:0770, lsa, lsg);
                        mkdir(wwwGroupDir+"/server/classes", tomcat.isSecure()?0750:0770, lsa, lsg);
                        mkdir(wwwGroupDir+"/server/lib", tomcat.isSecure()?0750:0770, lsa, lsg);
                        lnAll("../../../.."+tomcatDirectory+"/server/lib/", wwwGroupDir+"/server/lib/", lsaUID, lsgGID);

                        if(
                            version.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)
                            || isTomcat55
                        ) {
                            mkdir(wwwGroupDir+"/server/webapps", tomcat.isSecure()?0750:0770, lsa, lsg);
                        }
                    }

                    /*
                     * The shared directory
                     */
                    if(
                        version.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)
                        || isTomcat55
                    ) {
                        mkdir(wwwGroupDir+"/shared", tomcat.isSecure()?0750:0770, lsa, lsg);
                        mkdir(wwwGroupDir+"/shared/classes", tomcat.isSecure()?0750:0770, lsa, lsg);
                        mkdir(wwwGroupDir+"/shared/lib", tomcat.isSecure()?0750:0770, lsa, lsg);
                    }

                    //  008
                    if(!isTomcat4) {
                        UnixFile servErrUF = new UnixFile(varUF, "log/servlet_err");
                        servErrUF.getSecureOutputStream(lsaUID, lsgGID, 0640, false).close();
                    }

                    // Set the ownership to avoid future rebuilds of this directory
                    wwwGroupDirUF.chown(lsaUID, lsgGID);
                    
                    needRestart=true;
                }

                // always rebuild profile.sites file
                UnixFile newSitesFileUF = new UnixFile(wwwGroupDirUF, "bin/profile.sites.new");
                ChainWriter out = new ChainWriter(
                    new BufferedOutputStream(
                        newSitesFileUF.getSecureOutputStream(lsaUID, lsgGID, 0750, true)
                    )
                );
		List<HttpdTomcatSharedSite> sites = tomcat.getHttpdTomcatSharedSites();
		try {
		    out.print("export SITES=\"");
		    boolean didOne=false;
		    for(int j = 0; j< sites.size(); j++) {
			HttpdSite hs=sites.get(j).getHttpdTomcatSite().getHttpdSite();
			if(hs.getDisableLog()==null) {
			    if (didOne) out.print(' ');
			    else didOne=true;
			    out.print(hs.getSiteName());
			}
		    }
		    out.print("\"\n");
		} finally {
		    out.flush();
		    out.close();
		}
                // flag as needing a restart if this file is different than any existing
                UnixFile sitesFile = new UnixFile(wwwGroupDirUF, "bin/profile.sites");
                if(!sitesFile.exists() || !newSitesFileUF.contentEquals(sitesFile)) {
                    needRestart=true;
                    UnixFile backupFile=new UnixFile(wwwGroupDirUF, "bin/profile.sites.old");
                    if(sitesFile.exists()) sitesFile.renameTo(backupFile);
                    newSitesFileUF.renameTo(sitesFile);
                } else newSitesFileUF.delete();

                if(isTomcat4 && tomcat.isSecure()) {
                    UnixFile buildPolicy=new UnixFile(wwwGroupDirUF, "bin/build_catalina_policy");
                    if(!tomcat.isManual() || buildPolicy.exists()) {
                        // always rebuild the bin/build_catalina_policy script for secured and auto JVMs
                        UnixFile newBuildPolicy=new UnixFile(wwwGroupDirUF, "bin/build_catalina_policy.new");
                        out=new ChainWriter(
                            new BufferedOutputStream(
                                newBuildPolicy.getSecureOutputStream(lsaUID, lsgGID, 0700, true)
                            )
                        );
			try {
			    out.print("#!/bin/sh\n"
				      + ". /usr/aoserv/sbin/build_catalina_policy\n"
				      + "buildCatalinaPolicy '"+HttpdSharedTomcat.WWW_GROUP_DIR+'/').print(tomcat.getName()).print("' ")
                                .print(tomcat.getTomcat4Worker().getNetBind().getPort().getPort()).print(' ')
                                .print(tomcat.getTomcat4ShutdownPort().getPort().getPort());
			    for (int j = 0; j< sites.size(); j++) {
				HttpdSite hs=sites.get(j).getHttpdTomcatSite().getHttpdSite();
				if(hs.getDisableLog()==null) out.print(" '").print(hs.getSiteName()).print('\'');
			    }
			    out.print('\n');
			} finally {
			    out.flush();
			    out.close();
			}

                        // flag as needing a restart if this file is different than any existing
                        if(!buildPolicy.exists() || !newBuildPolicy.contentEquals(buildPolicy)) {
                            needRestart=true;
                            UnixFile backupBuildPolicy = new UnixFile(wwwGroupDirUF, "bin/build_catalina_policy.old");
                            if(buildPolicy.exists()) buildPolicy.renameTo(backupBuildPolicy);
                            newBuildPolicy.renameTo(buildPolicy);
                        } else newBuildPolicy.delete();
                    }
                }

                // make work directories and remove extra work dirs
                List<String> workFiles = new SortedArrayList<String>();
                String[] wlist = (innerWorkUF==null?workUF:innerWorkUF).getFile().list();
		if(wlist!=null) {
		    for (int j = 0; j<wlist.length; j++) {
			workFiles.add(wlist[j]);
		    }
		}
                for (int j = 0; j< sites.size(); j++) {
                    HttpdSite hs=sites.get(j).getHttpdTomcatSite().getHttpdSite();
                    if(hs.getDisableLog()==null) {
                        String subwork= isTomcat4?hs.getPrimaryHttpdSiteURL().getHostname():hs.getSiteName();
                        workFiles.remove(subwork);
                        UnixFile workDir = new UnixFile((innerWorkUF==null?workUF:innerWorkUF), subwork);
                        if (!workDir.exists()) {
                            workDir
                                .mkdir()
                                .chown(
                                    lsaUID, 
                                    sites.get(j)
                                        .getHttpdTomcatSite()
                                        .getHttpdSite()
                                        .getLinuxServerGroup()
                                        .getGID()
                                        .getID()
                                )
                                .setMode(0750)
                            ;
                        }
                    }
                }
                for (int c = 0; c < workFiles.size(); c++) {
                    deleteFileList.add(new File((innerWorkUF==null?workUF:innerWorkUF).getFile(), workFiles.get(c)));
                }

                // Make sure the newly created or existing web site is not removed
                wwwGroupDirectories.remove(tomcat.getName());

                /*
                 * Add the web server config files if they do not exist.
                 */
                // The shared config part
                String config = CONF_WWWGROUPS + '/' + tomcat.getName();
                groupConfFiles.remove(tomcat.getName());
                UnixFile configFile = new UnixFile(config);
                if (!configFile.exists()) {
                    // Add the /etc/httpd/conf/wwwgroup/shared_tomcat_name file
                    out=new ChainWriter(
                        configFile.getSecureOutputStream(UnixFile.ROOT_UID, lsgGID, 0640, false)
                    );
		    try {
			if(!isTomcat4) {
			    out.print("<IfModule mod_jserv.c>\n"
				      + "    ApJServDefaultProtocol ajpv12\n"
				      + "    ApJServSecretKey DISABLED\n"
				      + "    ApJServMountCopy on\n"
				      + "    ApJServLogLevel notice\n"
				      + "    ApJServMount default /root\n"
				      + "    AddType text/do .do\n"
				      + "    AddHandler jserv-servlet .do\n"
				      + "    AddType text/jsp .jsp\n"
				      + "    AddHandler jserv-servlet .jsp\n"
				      + "    AddType text/xml .xml\n"
				      + "    AddHandler jserv-servlet .xml\n"
				      + "    <IfModule mod_rewrite.c>\n"
				      + "        RewriteEngine on\n"
				      + "        RewriteRule ^(/.*;jsessionid=.*)$ $1 [T=jserv-servlet]\n"
				      + "    </IfModule>\n"
				      + "</IfModule>\n"
				      + "\n");
			    if (version.startsWith(HttpdTomcatVersion.VERSION_3_1_PREFIX)) out.print("Include conf/webapps/examples\n");
			    else out.print("Include conf/webapps/examples-").print(version).print('\n');
			}
		    } finally {
			out.flush();
			out.close();
		    }
                }

                // Rebuild the server.xml for Tomcat 4 and Tomcat 5 JVMs
                if(isTomcat4) {
                    String autoWarning=getAutoWarning(tomcat);
                    String confServerXML=HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcat.getName()+"/conf/server.xml";
                    UnixFile confServerXMLUF=new UnixFile(confServerXML);
                    if(!tomcat.isManual() || !confServerXMLUF.exists()) {
                        String newConfServerXML=HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcat.getName()+"/conf/server.xml.new";
                        UnixFile newConfServerXMLUF=new UnixFile(newConfServerXML);
                        out=new ChainWriter(
                            new BufferedOutputStream(
                                newConfServerXMLUF.getSecureOutputStream(lsaUID, lsgGID, tomcat.isSecure()?0600:0660, true)
                            )
                        );
			try {
                            HttpdWorker hw=tomcat.getTomcat4Worker();
                            if(!tomcat.isManual()) out.print(autoWarning);
                            NetBind shutdownPort = tomcat.getTomcat4ShutdownPort();
                            if(shutdownPort==null) throw new SQLException("Unable to find shutdown key for HttpdSharedTomcat: "+tomcat);
                            String shutdownKey=tomcat.getTomcat4ShutdownKey();
                            if(shutdownKey==null) throw new SQLException("Unable to find shutdown key for HttpdSharedTomcat: "+tomcat);
                            out.print("<Server port=\"").print(shutdownPort.getPort().getPort()).print("\" shutdown=\"").print(shutdownKey).print("\" debug=\"0\">\n");
                            if(isTomcat55) {
                                out.print("  <GlobalNamingResources>\n"
                                        + "    <Resource name=\"UserDatabase\" auth=\"Container\"\n"
                                        + "              type=\"org.apache.catalina.UserDatabase\"\n"
                                        + "       description=\"User database that can be updated and saved\"\n"
                                        + "           factory=\"org.apache.catalina.users.MemoryUserDatabaseFactory\"\n"
                                        + "          pathname=\"conf/tomcat-users.xml\" />\n"
                                        + "   </GlobalNamingResources>\n");
                            } else if(version.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)) {
                                out.print("  <Listener className=\"org.apache.catalina.mbeans.ServerLifecycleListener\" debug=\"0\"/>\n"
                                        + "  <Listener className=\"org.apache.catalina.mbeans.GlobalResourcesLifecycleListener\" debug=\"0\"/>\n"
                                        + "  <GlobalNamingResources>\n"
                                        + "    <Resource name=\"UserDatabase\" auth=\"Container\" type=\"org.apache.catalina.UserDatabase\" description=\"User database that can be updated and saved\"/>\n"
                                        + "    <ResourceParams name=\"UserDatabase\">\n"
                                        + "      <parameter>\n"
                                        + "        <name>factory</name>\n"
                                        + "        <value>org.apache.catalina.users.MemoryUserDatabaseFactory</value>\n"
                                        + "      </parameter>\n"
                                        + "      <parameter>\n"
                                        + "        <name>pathname</name>\n"
                                        + "        <value>conf/tomcat-users.xml</value>\n"
                                        + "      </parameter>\n"
                                        + "    </ResourceParams>\n"
                                        + "  </GlobalNamingResources>\n");
                            }
                            out.print("  <Service name=\"").print(isTomcat55 ? "Catalina" : "Tomcat-Apache").print("\">\n"
                                    + "    <Connector\n");
                                      /*
                                    + "      className=\"org.apache.coyote.tomcat4.CoyoteConnector\"\n"
                                    + "      port=\"").print(hw.getNetBind().getPort().getPort()).print("\"\n"
                                    + "      minProcessors=\"2\"\n"
                                    + "      maxProcessors=\"200\"\n"
                                    + "      enableLookups=\"true\"\n"
                                    + "      redirectPort=\"443\"\n"
                                    + "      acceptCount=\"10\"\n"
                                    + "      debug=\"0\"\n"
                                    + "      connectionTimeout=\"20000\"\n"
                                    + "      useURIValidationHack=\"false\"\n"
                                    + "      protocolHandlerClassName=\"org.apache.jk.server.JkCoyoteHandler\"\n"
                                      */
                            if (version.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)) {
                                out.print("      className=\"org.apache.ajp.tomcat4.Ajp13Connector\"\n");
                            }
                            out.print("      port=\"").print(hw.getNetBind().getPort().getPort()).print("\"\n");
                            if(isTomcat55) out.print("      enableLookups=\"false\"\n");
                            out.print("      minProcessors=\"2\"\n"
                                    + "      maxProcessors=\"200\"\n"
                                    + "      address=\""+IPAddress.LOOPBACK_IP+"\"\n"
                                    + "      acceptCount=\"10\"\n"
                                    + "      debug=\"0\"\n"
                                    + "      protocol=\"AJP/1.3\"\n"
                                    + "    />\n"
                                    + "    <Engine name=\"").print(isTomcat55 ? "Catalina" : "Tomcat-Apache").print("\" defaultHost=\"localhost\" debug=\"0\">\n");
                            if(isTomcat55) {
                                out.print("      <Realm className=\"org.apache.catalina.realm.UserDatabaseRealm\"\n"
                                        + "          resourceName=\"UserDatabase\" />\"\n");
                            } else if(version.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)) {
                                out.print("      <Logger\n"
                                        + "        className=\"org.apache.catalina.logger.FileLogger\"\n"
                                        + "        directory=\"var/log\"\n"
                                        + "        prefix=\"catalina_log.\"\n"
                                        + "        suffix=\".txt\"\n"
                                        + "        timestamp=\"true\"\n"
                                        + "      />\n"
                                        + "      <Realm className=\"org.apache.catalina.realm.UserDatabaseRealm\" debug=\"0\" resourceName=\"UserDatabase\" />\n");
                            } else {
                                out.print("      <Realm className=\"org.apache.catalina.realm.MemoryRealm\" />\n");
                            }
                            for(int c=0;c<sites.size();c++) {
                                HttpdSite hs=sites.get(c).getHttpdTomcatSite().getHttpdSite();
                                if(hs.getDisableLog()==null) {
                                    String primaryHostname=hs.getPrimaryHttpdSiteURL().getHostname();
                                    out.print("      <Host\n"
                                            + "        name=\"").print(primaryHostname).print("\"\n"
                                            + "        debug=\"0\"\n"
                                            + "        appBase=\""+HttpdSite.WWW_DIRECTORY+'/').print(hs.getSiteName()).print("/webapps\"\n"
                                            + "        unpackWARs=\"true\"\n");
                                    if(
                                        version.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)
                                        || isTomcat55
                                    ) {
                                        out.print("        autoDeploy=\"true\"\n");
                                    }
                                    if(isTomcat55) {
                                        out.print("        xmlValidation=\"false\"\n"
                                                + "        xmlNamespaceAware=\"false\"\n");
                                    }
                                    out.print("      >\n");
                                    List<String> usedHostnames=new SortedArrayList<String>();
                                    usedHostnames.add(primaryHostname);
                                    List<HttpdSiteBind> binds=hs.getHttpdSiteBinds();
                                    for(int d=0;d<binds.size();d++) {
                                        HttpdSiteBind bind=binds.get(d);
                                        List<HttpdSiteURL> urls=bind.getHttpdSiteURLs();
                                        for(int e=0;e<urls.size();e++) {
                                            String hostname=urls.get(e).getHostname();
                                            if(!usedHostnames.contains(hostname)) {
                                                out.print("        <Alias>").print(hostname).print("</Alias>\n");
                                                usedHostnames.add(hostname);
                                            }
                                        }
                                        // When listed first, also include the IP addresses as aliases
                                        if(hs.listFirst()) {
                                            String ip=bind.getHttpdBind().getNetBind().getIPAddress().getIPAddress();
                                            if(!usedHostnames.contains(ip)) {
                                                out.print("        <Alias>").print(ip).print("</Alias>\n");
                                                usedHostnames.add(ip);
                                            }
                                        }
                                    }
                                    if (version.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)){
                                        out.print("        <Logger\n"
                                                + "          className=\"org.apache.catalina.logger.FileLogger\"\n"
                                                + "          directory=\""+HttpdSite.WWW_DIRECTORY+'/').print(hs.getSiteName()).print("/var/log\"\n"
                                                + "          prefix=\"").print(primaryHostname).print("_log.\"\n"
                                                + "          suffix=\".txt\"\n"
                                                + "          timestamp=\"true\"\n"
                                                + "        />\n");
                                    }
                                    HttpdTomcatSite tomcatSite=hs.getHttpdTomcatSite();
                                    for(HttpdTomcatContext htc : tomcatSite.getHttpdTomcatContexts()) {
                                        out.print("        <Context\n");
                                        if(htc.getClassName()!=null) out.print("          className=\"").print(htc.getClassName()).print("\"\n");
                                        out.print("          cookies=\"").print(htc.useCookies()).print("\"\n"
                                                + "          crossContext=\"").print(htc.allowCrossContext()).print("\"\n"
                                                + "          docBase=\"").print(htc.getDocBase()).print("\"\n"
                                                + "          override=\"").print(htc.allowOverride()).print("\"\n"
                                                + "          path=\"").print(htc.getPath()).print("\"\n"
                                                + "          privileged=\"").print(htc.isPrivileged()).print("\"\n"
                                                + "          reloadable=\"").print(htc.isReloadable()).print("\"\n"
                                                + "          useNaming=\"").print(htc.useNaming()).print("\"\n");
                                        if(htc.getWrapperClass()!=null) out.print("          wrapperClass=\"").print(htc.getWrapperClass()).print("\"\n");
                                        out.print("          debug=\"").print(htc.getDebugLevel()).print("\"\n");
                                        if(htc.getWorkDir()!=null) out.print("          workDir=\"").print(htc.getWorkDir()).print("\"\n");
                                        List<HttpdTomcatParameter> parameters=htc.getHttpdTomcatParameters();
                                        List<HttpdTomcatDataSource> dataSources=htc.getHttpdTomcatDataSources();
                                        if(parameters.isEmpty() && dataSources.isEmpty()) {
                                            out.print("        />\n");
                                        } else {
                                            out.print("        >\n");
                                            // Parameters
                                            for(HttpdTomcatParameter parameter : parameters) {
                                                writeHttpdTomcatParameter(version, parameter, out);
                                            }
                                            // Data Sources
                                            for(HttpdTomcatDataSource dataSource : dataSources) {
                                                writeHttpdTomcatDataSource(version, dataSource, out);
                                            }
                                            out.print("        </Context>\n");
                                        }
                                    }
                                    out.print("      </Host>\n");
                                }
                            }
                            out.print("    </Engine>\n"
                                    + "  </Service>\n"
                                    + "</Server>\n");
			} finally {
			    out.flush();
			    out.close();
			}

                        // Must restart JVM if this file has changed
                        if(
                            !confServerXMLUF.exists()
                            || !newConfServerXMLUF.contentEquals(confServerXMLUF)
                        ) {
                            needRestart=true;
                            newConfServerXMLUF.renameTo(confServerXMLUF);
                        } else newConfServerXMLUF.delete();
                    } else {
                        try {
                            stripFilePrefix(
                                confServerXMLUF,
                                autoWarning
                            );
                        } catch(IOException err) {
                            // Errors OK because this is done in manual mode and they might have symbolic linked stuff
                        }
                    }
                }
                
                // Start if needed
                if(needRestart && tomcat.getDisableLog()==null) {
                    int pkey=tomcat.getPKey();
                    if(!sharedTomcatsNeedingRestarted.contains(pkey)) sharedTomcatsNeedingRestarted.add(pkey);
                }
            }

            /*
             * Delete any extra files once the rebuild is done
             */
            for (int c = 0; c < groupConfFiles.size(); c++)
                deleteFileList.add(new File(CONF_WWWGROUPS, groupConfFiles.get(c)));
            for (int c = 0; c < wwwGroupDirectories.size(); c++) {
                String tomcatName=wwwGroupDirectories.get(c);
                stopSharedTomcat(tomcatName);
                disableSharedTomcat(tomcatName);
                String fullPath = HttpdSharedTomcat.WWW_GROUP_DIR + '/' + tomcatName;
                if (!aoServer.isHomeUsed(fullPath)) deleteFileList.add(new File(fullPath));
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /*public static void initializeHttpdSitePasswdFile(int sitePKey, String username, String encPassword) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "initializeHttpdSitePasswdFile(int,String,String)", null);
        try {
            AOServConnector conn=AOServDaemon.getConnector();

            HttpdSite httpdSite=conn.httpdSites.getHttpdSite(sitePKey);
            AOServer thisAOServer=AOServDaemon.getThisAOServer();
            if(!httpdSite.getAOServer().equals(thisAOServer)) throw new SQLException("HttpdSite #"+sitePKey+" has server of "+httpdSite.getAOServer().getServer().getHostname()+", which is not this server ("+thisAOServer.getServer().getHostname()+')');

            LinuxServerAccount lsa=httpdSite.getLinuxServerAccount();
            LinuxServerGroup lsg=httpdSite.getLinuxServerGroup();

            String passwdFile=httpdSite.getInstallDirectory()+"/conf/passwd";
            File file=new File(passwdFile);
            ChainWriter out=new ChainWriter(
                new UnixFile(passwdFile).getSecureOutputStream(
                   lsa.getUID().getID(),
                   lsg.getGID().getID(),
                   0644,
                   true
                )
            );
	    try {
		out.print(username).print(':').print(encPassword).print('\n');
	    } finally {
		out.flush();
		out.close();
	    }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }*/

    private static final boolean installArchive(HttpdSite site, String installDir) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "installArchive(HttpdSite,String)", null);
        try {
            // supported extensions:
            //	.tar				/bin/tar -C install_dir -x -f content_src
            //	.tgz / .tar.gz		/bin/tar -C install_dir -x -z -f content_src
            //	.zip / .jar / .war	/usr/j2sdk1.4.2_04/bin/jar -C install_dir -x -f content_src
            //	.bz2				/bin/tar -C install_dir -x --bzip -f content_src

            String contentSrc = site.getContentSrc();
            if (contentSrc==null) return false;

            String command;
            String ext = contentSrc.substring(contentSrc.length()-3);
            if (ext.equals("tar")) {
                command = "/bin/tar -C "+installDir+" -x -f "+contentSrc;
            } else if (ext.equals("tgz") || ext.equals(".gz")) {
                command = "/bin/tar -C "+installDir+" -x -z -f "+contentSrc;
            } else if (ext.equals("zip") || (ext.equals("jar") || (ext.equals("war")))) {
                command = getDefaultJdkPath(site.getAOServer().getServer().getOperatingSystemVersion().getPKey())+"/bin/jar -C "+installDir+" -x -f "+contentSrc;
            } else if (ext.equals("bz2")) {
                command = "/bin/tar -C "+installDir+" -x --bzip -f "+contentSrc;
            } else {
                File file=new File(contentSrc);
                if(file.isDirectory()) {
                    StringBuilder SB=new StringBuilder();
                    SB.append("/bin/cp -rdp");
                    String[] list=file.list();
                    int count=0;
                    if(list!=null) {
                        AutoSort.sortStatic(list);
                        for(int c=0;c<list.length;c++) {
                            String filename=list[c];
                            // Only accept if all characters valid
                            boolean good=true;
                            for(int d=0;d<filename.length();d++) {
                                char ch=filename.charAt(d);
                                if(
                                    (ch<'a' || ch>'z')
                                    && (ch<'A' || ch>'Z')
                                    && (ch<'0' || ch>'9')
                                    && ch!='-'
                                    && ch!='_'
                                    && ch!='.'
                                ) {
                                    good=false;
                                    break;
                                }
                            }
                            if(good) {
                                SB.append(' ').append(filename);
                                count++;
                            }
                        }
                    }
                    if(count==0) return false;
                    SB.append(' ').append(installDir);
                    command=SB.toString();
                } else throw new IllegalArgumentException("Content archive is of an unsupported file type: "+contentSrc);
            }

            String username = site.getLinuxServerAccount().getLinuxAccount().getUsername().getUsername();
            try {
                AOServDaemon.suexec(username, command);
                return true;
            } catch (IOException err) {
                AOServDaemon.reportError(err, null);
                return false;
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void ln(String destination, String filename, int uid, int gid) throws IOException {
        Profiler.startProfile(Profiler.IO, HttpdManager.class, "ln(String,String,int,int)", null);
        try {
            new UnixFile(filename).symLink(destination).chown(uid, gid);
        } catch (IOException e) {
            System.err.println("ln: filename: "+filename+"   destination: "+destination);
            throw e;
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }
    
    public static void lnAll(String dstBase, String srcBase, int uid, int gid) throws IOException {
        Profiler.startProfile(Profiler.IO, HttpdManager.class, "lnAll(String,String,int,int)", null);
        try {
            String[] destinations=new UnixFile(dstBase).list();
            for (int i=0;i<destinations.length;i++) {
                new UnixFile(srcBase+destinations[i]).symLink(dstBase+destinations[i]).chown(uid, gid);
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    public static void mkdir(String dirName, int mode, LinuxServerAccount owner, LinuxServerGroup group) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, HttpdManager.class, "mkdir(String,int,LinuxServerAccount,LinuxServerGroup)", null);
        try {
            UnixFile uf=new UnixFile(dirName);
            if(!uf.exists()) uf.mkdir();
            else if(!uf.isDirectory()) throw new IOException("File exists and is not a directory: "+dirName);
            uf.chown(owner.getUID().getID(), group.getGID().getID()).setMode(mode);
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    public static boolean passwordMatches(String password, String crypted) throws IOException {
        Profiler.startProfile(Profiler.FAST, HttpdManager.class, "passwordMatches(String,String)", null);
        try {
            if(crypted.length()<=2) return false;
            String salt=crypted.substring(0,2);
            return UnixCrypt.crypt(password, salt).equals(crypted);
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static final Object reloadLock=new Object();
    private static void reload(IntList serversNeedingRestarted) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "reload(IntList)", null);
        try {
            HttpdServerTable hst=AOServDaemon.getConnector().httpdServers;
            for(int c=0;c<serversNeedingRestarted.size();c++) {
                reload(hst.get(serversNeedingRestarted.getInt(c)));
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    private static void reload(HttpdServer hs) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "reload(HttpdServer)", null);
        try {
            synchronized(reloadLock) {
                String[] cmd={
                    "/etc/rc.d/init.d/httpd"+hs.getNumber(),
                    "reload"
                };
                AOServDaemon.exec(cmd);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void controlApache(String command) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "controlApache(String)", null);
        try {
            synchronized(reloadLock) {
                for(HttpdServer hs : AOServDaemon.getThisAOServer().getHttpdServers()) {
                    String[] cmd={
                        "/etc/rc.d/init.d/httpd"+hs.getNumber(),
                        command
                    };
                    AOServDaemon.exec(cmd);
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void restartApache() throws IOException, SQLException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, HttpdManager.class, "restartApache()", null);
        try {
            controlApache("restart");
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public static void startApache() throws IOException, SQLException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, HttpdManager.class, "startApache()", null);
        try {
            controlApache("start");
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public static void stopApache() throws IOException, SQLException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, HttpdManager.class, "stopApache()", null);
        try {
            controlApache("stop");
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(HttpdManager.class) && httpdManager==null) {
                synchronized(System.out) {
                    if(httpdManager==null) {
                        System.out.print("Starting HttpdManager: ");
                        AOServConnector connector=AOServDaemon.getConnector();
                        httpdManager=new HttpdManager();
                        connector.httpdBinds.addTableListener(httpdManager, 0);
                        connector.httpdServers.addTableListener(httpdManager, 0);
                        connector.httpdJBossSites.addTableListener(httpdManager, 0);
                        connector.httpdSharedTomcats.addTableListener(httpdManager, 0);
                        connector.httpdSites.addTableListener(httpdManager, 0);
                        connector.httpdSiteBinds.addTableListener(httpdManager, 0);
                        connector.httpdSiteURLs.addTableListener(httpdManager, 0);
                        connector.httpdStaticSites.addTableListener(httpdManager, 0);
                        connector.httpdTomcatContexts.addTableListener(httpdManager, 0);
                        connector.httpdTomcatDataSources.addTableListener(httpdManager, 0);
                        connector.httpdTomcatParameters.addTableListener(httpdManager, 0);
                        connector.httpdTomcatSites.addTableListener(httpdManager, 0);
                        connector.httpdTomcatSharedSites.addTableListener(httpdManager, 0);
                        connector.httpdTomcatStdSites.addTableListener(httpdManager, 0);
                        connector.httpdWorkers.addTableListener(httpdManager, 0);
                        connector.ipAddresses.addTableListener(httpdManager, 0);
                        connector.linuxServerAccounts.addTableListener(httpdManager, 0);
                        connector.netBinds.addTableListener(httpdManager, 0);
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static String startJVM(int sitePKey) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "startJVM(int)", null);
        try {
            synchronized(startJVMTimes) {
                AOServConnector conn=AOServDaemon.getConnector();

                HttpdSite httpdSite=conn.httpdSites.get(sitePKey);
                AOServer thisAOServer=AOServDaemon.getThisAOServer();
                if(!httpdSite.getAOServer().equals(thisAOServer)) throw new SQLException("HttpdSite #"+sitePKey+" has server of "+httpdSite.getAOServer().getServer().getHostname()+", which is not this server ("+thisAOServer.getServer().getHostname()+')');

                HttpdTomcatSite tomcatSite=httpdSite.getHttpdTomcatSite();
                if(tomcatSite==null) throw new SQLException("Unable to find HttpdTomcatSite for HttpdSite #"+sitePKey);

                HttpdTomcatStdSite stdSite=tomcatSite.getHttpdTomcatStdSite();
                if(stdSite!=null) {
                    String key="httpd_tomcat_std_site.tomcat_site="+sitePKey;

                    // Throw an exception if the site was started recently.
                    Long L=startJVMTimes.get(key);
                    if(L!=null) {
                        long span=System.currentTimeMillis()-L.longValue();
                        if(span<HttpdTomcatSite.MINIMUM_START_JVM_DELAY && span>=0) return
                            "Must wait "
                            +SQLUtility.getMilliDecimal(HttpdTomcatSite.MINIMUM_START_JVM_DELAY)
                            +" seconds between Java VM starts, only "
                            +SQLUtility.getMilliDecimal((int)span)
                            +" seconds have passed."
                        ;
                    }

                    LinuxServerAccount lsa=httpdSite.getLinuxServerAccount();
                    AOServDaemon.suexec(
                        lsa.getLinuxAccount().getUsername().getUsername(),
                        HttpdSite.WWW_DIRECTORY+'/'+httpdSite.getSiteName()+"/bin/tomcat start"
                    );

                    // Make sure we don't start too quickly
                    startJVMTimes.put(key, Long.valueOf(System.currentTimeMillis()));
                } else {
                    HttpdTomcatSharedSite shrSite=tomcatSite.getHttpdTomcatSharedSite();
                    if(shrSite!=null) {
                        HttpdSharedTomcat shrTomcat=shrSite.getHttpdSharedTomcat();
                        String name=shrTomcat.getName();
                        String key="httpd_shared_tomcats.name="+name;

                        // Throw an exception if the site was started recently.
                        Long L=startJVMTimes.get(key);
                        if(L!=null) {
                            long span=System.currentTimeMillis()-L.longValue();
                            if(span<HttpdTomcatSite.MINIMUM_START_JVM_DELAY && span>=0) return
                                "Must wait "
                                +SQLUtility.getMilliDecimal(HttpdTomcatSite.MINIMUM_START_JVM_DELAY)
                                +" seconds between Java VM starts, only "
                                +SQLUtility.getMilliDecimal((int)span)
                                +" seconds have passed."
                            ;
                        }

                        startSharedTomcat(shrTomcat);

                        // Make sure we don't start too quickly
                        startJVMTimes.put(key, Long.valueOf(System.currentTimeMillis()));
                    } else {
                        HttpdJBossSite jbossSite=tomcatSite.getHttpdJBossSite();
                        if(jbossSite!=null) {
                            String key="httpd_jboss_site.tomcat_site="+sitePKey;

                            // Throw an exception if the site was started recently.
                            Long L=startJVMTimes.get(key);
                            if(L!=null) {
                                long span=System.currentTimeMillis()-L.longValue();
                                if(span<HttpdTomcatSite.MINIMUM_START_JVM_DELAY && span>=0) return
                                    "Must wait "
                                    +SQLUtility.getMilliDecimal(HttpdTomcatSite.MINIMUM_START_JVM_DELAY)
                                    +" seconds between Java VM starts, only "
                                    +SQLUtility.getMilliDecimal((int)span)
                                    +" seconds have passed."
                                ;
                            }

                            LinuxServerAccount lsa=httpdSite.getLinuxServerAccount();
                            AOServDaemon.suexec(
                                lsa.getLinuxAccount().getUsername().getUsername(),
                                HttpdSite.WWW_DIRECTORY+'/'+httpdSite.getSiteName()+"/bin/jboss start"
                            );

                            // Make sure we don't start too quickly
                            startJVMTimes.put(key, Long.valueOf(System.currentTimeMillis()));
                        } else throw new SQLException("Unable to find HttpdTomcatStdSite, HttpdTomcatSharedSite or HttpdJBossSite for HttpdSite #"+sitePKey);
                    }
                }

                // Null means all went well
                return null;
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static String stopJVM(int sitePKey) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "stopJVM(int)", null);
        try {
            synchronized(startJVMTimes) {
                AOServConnector conn=AOServDaemon.getConnector();

                HttpdSite httpdSite=conn.httpdSites.get(sitePKey);
                AOServer thisAOServer=AOServDaemon.getThisAOServer();
                if(!httpdSite.getAOServer().equals(thisAOServer)) throw new SQLException("HttpdSite #"+sitePKey+" has server of "+httpdSite.getAOServer().getServer().getHostname()+", which is not this server ("+thisAOServer.getServer().getHostname()+')');

                HttpdTomcatSite tomcatSite=httpdSite.getHttpdTomcatSite();
                if(tomcatSite==null) throw new SQLException("Unable to find HttpdTomcatSite for HttpdSite #"+sitePKey);

                HttpdTomcatStdSite stdSite=tomcatSite.getHttpdTomcatStdSite();
                if(stdSite!=null) {
                    String key="httpd_tomcat_std_site.tomcat_site="+sitePKey;

                    // Throw an exception if the site was started recently.
                    Long L=startJVMTimes.get(key);
                    if(L!=null) {
                        long span=System.currentTimeMillis()-L.longValue();
                        if(span<HttpdTomcatSite.MINIMUM_START_JVM_DELAY && span>=0) return
                            "Must wait "
                            +SQLUtility.getMilliDecimal(HttpdTomcatSite.MINIMUM_START_JVM_DELAY)
                            +" seconds between a Java VM start and stop, only "
                            +SQLUtility.getMilliDecimal((int)span)
                            +" seconds have passed."
                        ;
                    }

                    LinuxServerAccount lsa=httpdSite.getLinuxServerAccount();
                    AOServDaemon.suexec(
                        lsa.getLinuxAccount().getUsername().getUsername(),
                        HttpdSite.WWW_DIRECTORY+'/'+httpdSite.getSiteName()+"/bin/tomcat stop"
                    );

                    // Make sure we don't start too quickly
                    startJVMTimes.put(key, Long.valueOf(System.currentTimeMillis()));
                } else {
                    HttpdTomcatSharedSite shrSite=tomcatSite.getHttpdTomcatSharedSite();
                    if(shrSite!=null) {
                        HttpdSharedTomcat shrTomcat=shrSite.getHttpdSharedTomcat();
                        String name=shrTomcat.getName();
                        String key="httpd_shared_tomcats.name="+name;

                        // Throw an exception if the site was started recently.
                        Long L=startJVMTimes.get(key);
                        if(L!=null) {
                            long span=System.currentTimeMillis()-L.longValue();
                            if(span<HttpdTomcatSite.MINIMUM_START_JVM_DELAY && span>=0) return
                                "Must wait "
                                +SQLUtility.getMilliDecimal(HttpdTomcatSite.MINIMUM_START_JVM_DELAY)
                                +" seconds between a Java VM start and stop, only "
                                +SQLUtility.getMilliDecimal((int)span)
                                +" seconds have passed."
                            ;
                        }

                        stopSharedTomcat(shrTomcat);

                        // Make sure we don't start too quickly
                        startJVMTimes.put(key, Long.valueOf(System.currentTimeMillis()));
                    } else {
                        HttpdJBossSite jbossSite=tomcatSite.getHttpdJBossSite();
                        if(jbossSite!=null) {
                            String key="httpd_jboss_site.tomcat_site="+sitePKey;

                            // Throw an exception if the site was started recently.
                            Long L=startJVMTimes.get(key);
                            if(L!=null) {
                                long span=System.currentTimeMillis()-L.longValue();
                                if(span<HttpdTomcatSite.MINIMUM_START_JVM_DELAY && span>=0) return
                                    "Must wait "
                                    +SQLUtility.getMilliDecimal(HttpdTomcatSite.MINIMUM_START_JVM_DELAY)
                                    +" seconds between a Java VM start and stop, only "
                                    +SQLUtility.getMilliDecimal((int)span)
                                    +" seconds have passed."
                                ;
                            }

                            LinuxServerAccount lsa=httpdSite.getLinuxServerAccount();
                            AOServDaemon.suexec(
                                lsa.getLinuxAccount().getUsername().getUsername(),
                                HttpdSite.WWW_DIRECTORY+'/'+httpdSite.getSiteName()+"/bin/jboss stop"
                            );

                            // Make sure we don't start too quickly
                            startJVMTimes.put(key, Long.valueOf(System.currentTimeMillis()));
                        } else throw new SQLException("Unable to find HttpdTomcatStdSite, HttpdTomcatSharedSite or HttpdJBossSite for HttpdSite #"+sitePKey);
                    }
                }

                // Null means all went well
                return null;
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void waitForRebuild() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, HttpdManager.class, "waitForRebuild()", null);
        try {
            if(httpdManager!=null) httpdManager.waitForBuild();
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
    
    private static void disableAndEnableSharedTomcats(AOServer aoServer, AOServConnector connector, IntList needStarted) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "disableAndEnableSharedTomcats(AOServer,AOServConnector,IntList)", null);
        try {
            List<HttpdSharedTomcat> hsts=aoServer.getHttpdSharedTomcats();
            for(int c=0;c<hsts.size();c++) {
                HttpdSharedTomcat hst=hsts.get(c);
                String tomcatName=hst.getName();
                UnixFile pidUF=new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcatName+"/var/run/tomcat.pid");
                UnixFile daemonUF=new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcatName+"/daemon/tomcat");
                if(hst.getDisableLog()==null) {
                    // Enabled, make sure running and auto
                    if(needStarted.contains(hst.getPKey()) || !pidUF.exists()) startSharedTomcat(hst);
                    if(!daemonUF.exists()) enableSharedTomcat(hst);
                } else {
                    // Disabled, make sure stopped and not auto
                    if(daemonUF.exists()) disableSharedTomcat(hst);
                    if(pidUF.exists()) stopSharedTomcat(hst);
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    
    private static void disableAndEnableHttpdSites(AOServer aoServer, AOServConnector connector, IntList needStarted) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "disableAndEnableHttpdSites(AOServer,AOServConnector,IntList)", null);
        try {
            List<HttpdSite> hss=aoServer.getHttpdSites();
            for(int c=0;c<hss.size();c++) {
                HttpdSite hs=hss.get(c);
                String siteName=hs.getSiteName();
                try {
                    UnixFile ftpUF=new UnixFile("/var/ftp/pub/"+siteName);
                    if(hs.getDisableLog()==null) {
                        // Enabled, make sure running and auto
                        if(ftpUF.exists() && ftpUF.getUID()==UnixFile.ROOT_UID) ftpUF.chown(
                            hs.getLinuxServerAccount().getUID().getID(),
                            hs.getLinuxServerGroup().getGID().getID()
                        ).setMode(0775);
                        UnixFile pidUF=getPIDUnixFile(hs);
                        UnixFile daemonUF=getDaemonUnixFile(hs);
                        if(pidUF!=null && (needStarted.contains(hs.getPKey()) || !pidUF.exists())) {
                            startHttpdSite(hs);
                        }
                        if(daemonUF!=null && !daemonUF.exists()) enableHttpdSite(hs);
                    } else {
                        // Disabled, make sure stopped and not auto
                        if(ftpUF.exists() && ftpUF.getUID()!=UnixFile.ROOT_UID) ftpUF.chown(
                            UnixFile.ROOT_UID,
                            UnixFile.ROOT_GID
                        ).setMode(0700);
                        UnixFile daemonUF=getDaemonUnixFile(hs);
                        UnixFile pidUF=getPIDUnixFile(hs);
                        if(daemonUF!=null && daemonUF.exists()) disableHttpdSite(hs);
                        if(pidUF!=null && pidUF.exists()) stopHttpdSite(hs);
                    }
                } catch(IOException err) {
                    System.err.println("disableAndEnableHttpdSites error on site: "+siteName);
                    throw err;
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void startSharedTomcat(HttpdSharedTomcat hst) throws IOException, SQLException {
        Profiler.startProfile(Profiler.SLOW, HttpdManager.class, "startSharedTomcat()", null);
        try {
            LinuxServerAccount lsa=hst.getLinuxServerAccount();
            AOServDaemon.suexec(
                lsa.getLinuxAccount().getUsername().getUsername(),
                HttpdSharedTomcat.WWW_GROUP_DIR+'/'+hst.getName()+"/bin/tomcat start"
            );
        } finally {
            Profiler.endProfile(Profiler.SLOW);
        }
    }

    private static void stopSharedTomcat(HttpdSharedTomcat hst) throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, HttpdManager.class, "stopSharedTomcat(HttpdSharedTomcat)", null);
        try {
            stopSharedTomcat(hst.getLinuxServerAccount(), hst.getName());
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
    
    private static void stopSharedTomcat(String tomcatName) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "stopSharedTomcat(String)", null);
        try {
            UnixFile uf=new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcatName+"/daemon");
            if(uf.exists()) {
                int uid=uf.getUID();
                LinuxServerAccount lsa=AOServDaemon.getThisAOServer().getLinuxServerAccount(uid);
                if(lsa!=null) {
                    stopSharedTomcat(lsa, tomcatName);
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void stopSharedTomcat(LinuxServerAccount lsa, String tomcatName) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "stopSharedTomcat(LinuxServerAccount,String)", null);
        try {
            UnixFile pidUF=new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcatName+"/var/run/tomcat.pid");
            if(pidUF.exists()) {
                AOServDaemon.suexec(
                    lsa.getLinuxAccount().getUsername().getUsername(),
                    HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcatName+"/bin/tomcat stop"
                );
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void enableSharedTomcat(HttpdSharedTomcat hst) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "enableSharedTomcat(HttpdSharedTomcat)", null);
        try {
            UnixFile uf=new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR+'/'+hst.getName()+"/daemon/tomcat");
            if(!uf.exists()) uf.symLink("../bin/tomcat").chown(
                hst.getLinuxServerAccount().getUID().getID(),
                hst.getLinuxServerGroup().getGID().getID()
            );
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void disableSharedTomcat(HttpdSharedTomcat hst) throws IOException {
        Profiler.startProfile(Profiler.FAST, HttpdManager.class, "disableSharedTomcat(HttpdSharedTomcat)", null);
        try {
            disableSharedTomcat(hst.getName());
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static void disableSharedTomcat(String tomcatName) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "disableSharedTomcat(String)", null);
        try {
            UnixFile uf=new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcatName+"/daemon/tomcat");
            if(uf.exists()) uf.delete();
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static UnixFile getPIDUnixFile(HttpdSite hs) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "getPIDUnixFile(HttpdSite)", null);
        try {
            HttpdTomcatSite hts=hs.getHttpdTomcatSite();
            if(hts!=null) {
                HttpdJBossSite hjs=hts.getHttpdJBossSite();
                if(hjs!=null) return new UnixFile(hs.getInstallDirectory()+"/var/run/jboss.pid");
                HttpdTomcatStdSite htss=hts.getHttpdTomcatStdSite();
                if(htss!=null) return new UnixFile(hs.getInstallDirectory()+"/var/run/tomcat.pid");
            }
            return null;
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static UnixFile getDaemonUnixFile(HttpdSite hs) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "getDaemonUnixFile(HttpdSite)", null);
        try {
            HttpdTomcatSite hts=hs.getHttpdTomcatSite();
            if(hts!=null) {
                HttpdJBossSite hjs=hts.getHttpdJBossSite();
                if(hjs!=null) return new UnixFile(hs.getInstallDirectory()+"/daemon/jboss");
                HttpdTomcatStdSite htss=hts.getHttpdTomcatStdSite();
                if(htss!=null) return new UnixFile(hs.getInstallDirectory()+"/daemon/tomcat");
            }
            return null;
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void startHttpdSite(HttpdSite hs) throws IOException, SQLException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, HttpdManager.class, "startHttpdSite(HttpdSite)", null);
        try {
            controlHttpdSite(hs, "start");
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
    
    private static void stopHttpdSite(HttpdSite hs) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "stopHttpdSite(HttpdSite)", null);
        try {
            UnixFile pidUF=getPIDUnixFile(hs);
            if(pidUF!=null && pidUF.exists()) controlHttpdSite(hs, "stop");
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void controlHttpdSite(HttpdSite hs, String action) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "controlHttpdSite(HttpdSite,String)", null);
        try {
            HttpdTomcatSite hst=hs.getHttpdTomcatSite();
            if(hst!=null) {
                HttpdJBossSite hjs=hst.getHttpdJBossSite();
                if(hjs!=null) {
                    LinuxServerAccount lsa=hs.getLinuxServerAccount();
                    AOServDaemon.suexec(
                        lsa.getLinuxAccount().getUsername().getUsername(),
                        HttpdSite.WWW_DIRECTORY+'/'+hs.getSiteName()+"/bin/jboss "+action
                    );
                } else {
                    HttpdTomcatStdSite htss=hst.getHttpdTomcatStdSite();
                    if(htss!=null) {
                        LinuxServerAccount lsa=hs.getLinuxServerAccount();
                        AOServDaemon.suexec(
                            lsa.getLinuxAccount().getUsername().getUsername(),
                            HttpdSite.WWW_DIRECTORY+'/'+hs.getSiteName()+"/bin/tomcat "+action
                        );
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void stopHttpdSite(String siteName) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "stopHttpdSite(String)", null);
        try {
            UnixFile uf=new UnixFile(HttpdSite.WWW_DIRECTORY+'/'+siteName+"/daemon");
            if(uf.exists()) {
                int uid=uf.getUID();
                LinuxServerAccount lsa=AOServDaemon.getThisAOServer().getLinuxServerAccount(uid);
                if(lsa!=null) {
                    UnixFile jbossUF=new UnixFile(HttpdSite.WWW_DIRECTORY+'/'+siteName+"/bin/jboss");
                    if(jbossUF.exists()) {
                        UnixFile jbossPID=new UnixFile(HttpdSite.WWW_DIRECTORY+'/'+siteName+"/var/run/jboss.pid");
                        if(jbossPID.exists()) {
                            AOServDaemon.suexec(
                                lsa.getLinuxAccount().getUsername().getUsername(),
                                HttpdSite.WWW_DIRECTORY+'/'+siteName+"/bin/jboss stop"
                            );
                        }
                    }
                    UnixFile tomcatUF=new UnixFile(HttpdSite.WWW_DIRECTORY+'/'+siteName+"/bin/tomcat");
                    if(tomcatUF.exists()) {
                        UnixFile tomcatPID=new UnixFile(HttpdSite.WWW_DIRECTORY+'/'+siteName+"/var/run/tomcat.pid");
                        if(tomcatPID.exists()) {
                            AOServDaemon.suexec(
                                lsa.getLinuxAccount().getUsername().getUsername(),
                                HttpdSite.WWW_DIRECTORY+'/'+siteName+"/bin/tomcat stop"
                            );
                        }
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void enableHttpdSite(HttpdSite hs) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "enableHttpdSite(HttpdSite)", null);
        try {
            HttpdTomcatSite hst=hs.getHttpdTomcatSite();
            if(hst!=null) {
                HttpdJBossSite hjs=hst.getHttpdJBossSite();
                if(hjs!=null) {
                    UnixFile uf=new UnixFile(hs.getInstallDirectory()+"/daemon/jboss");
                    if(!uf.exists()) uf.symLink("../bin/jboss").chown(
                        hs.getLinuxServerAccount().getUID().getID(),
                        hs.getLinuxServerGroup().getGID().getID()
                    );
                } else {
                    HttpdTomcatStdSite htss=hst.getHttpdTomcatStdSite();
                    if(htss!=null) {
                        UnixFile uf=new UnixFile(hs.getInstallDirectory()+"/daemon/tomcat");
                        if(!uf.exists()) uf.symLink("../bin/tomcat").chown(
                            hs.getLinuxServerAccount().getUID().getID(),
                            hs.getLinuxServerGroup().getGID().getID()
                        );
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void disableHttpdSite(HttpdSite hs) throws IOException {
        Profiler.startProfile(Profiler.FAST, HttpdManager.class, "disableHttpdSite(HttpdSite)", null);
        try {
            disableHttpdSite(hs.getSiteName());
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static void disableHttpdSite(String siteName) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "disableHttpdSite(String)", null);
        try {
            UnixFile jbossUF=new UnixFile(HttpdSite.WWW_DIRECTORY+'/'+siteName+"/daemon/jboss");
            if(jbossUF.exists()) jbossUF.delete();

            UnixFile tomcatUF=new UnixFile(HttpdSite.WWW_DIRECTORY+'/'+siteName+"/daemon/tomcat");
            if(tomcatUF.exists()) tomcatUF.delete();
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void disableAndEnableSiteBinds(AOServer aoServer, AOServConnector connector) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "disableAndEnableSiteBinds(AOServer,AOServConnector)", null);
        try {
            List<HttpdSite> hss=aoServer.getHttpdSites();
            for(int c=0;c<hss.size();c++) {
                HttpdSite hs=hss.get(c);
                List<HttpdSiteBind> hsbs=hs.getHttpdSiteBinds();
                for(int e=0;e<hsbs.size();e++) {
                    HttpdSiteBind hsb=hsbs.get(e);
                    String predisableConfig=hsb.getPredisableConfig();
                    if(hsb.getDisableLog()==null) {
                        if(predisableConfig!=null) {
                            if(hsb.isManual()) {
                                NetBind nb=hsb.getHttpdBind().getNetBind();
                                String bindConfig=CONF_HOSTS+'/'+hs.getSiteName()+'_'+nb.getIPAddress().getIPAddress()+'_'+nb.getPort().getPort();
                                //String bindConfig=CONF_HOSTS+'/'+hs.getSiteName()+'.'+hsb.getHttpdBind().getNetBind().getPort().getPort();
                                UnixFile bindFile=new UnixFile(bindConfig);
                                String newConfig=bindConfig+".new";
                                UnixFile newFile=new UnixFile(newConfig);
                                PrintWriter out=new PrintWriter(
                                    newFile.getSecureOutputStream(
                                        UnixFile.ROOT_UID,
                                        hs.getLinuxServerGroup().getGID().getID(),
                                        0640,
                                        false
                                    )
                                );
				try {
				    out.print(predisableConfig);
				    out.print('\n');
				} finally {
				    out.flush();
				    out.close();
				}
                                newFile.renameTo(bindFile);
                            }
                            hsb.setPredisableConfig(null);
                        }
                    } else {
                        if(hsb.isManual() && predisableConfig==null) {
                            NetBind nb=hsb.getHttpdBind().getNetBind();
                            String bindConfig=CONF_HOSTS+'/'+hs.getSiteName()+'_'+nb.getIPAddress().getIPAddress()+'_'+nb.getPort().getPort();
                            //String bindConfig=CONF_HOSTS+'/'+hs.getSiteName()+'.'+hsb.getHttpdBind().getNetBind().getPort().getPort();
                            UnixFile bindFile=new UnixFile(bindConfig);
                            String newConfig=bindConfig+".new";
                            UnixFile newFile=new UnixFile(newConfig);
                            // Get the existing config
                            StringBuilder SB=new StringBuilder();
                            InputStream in=new BufferedInputStream(bindFile.getSecureInputStream());
			    try {
				int ch;
				while((ch=in.read())!=-1) SB.append((char)ch);
			    } finally {
				in.close();
			    }
                            hsb.setPredisableConfig(SB.toString());
                            // Overwrite the config with the disabled include
                            writeHttpdSiteBindFile(hsb, newFile, HttpdSite.DISABLED);
                            newFile.renameTo(bindFile);
                        }
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public String getProcessTimerDescription() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, HttpdManager.class, "getProcessTimerDescription()", null);
        try {
            return "Rebuild HTTPD";
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
    
    public long getProcessTimerMaximumTime() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, HttpdManager.class, "getProcessTimerMaximumTime()", null);
        try {
            return 15L*60*1000;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
    
    public static String getAutoWarning(HttpdSite hs) throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, HttpdManager.class, "getAutoWarning(HttpdSite)", null);
        try {
            return
                "<!--\n"
                + "  Warning: This file is automatically created by HttpdManager.  Any manual changes\n"
                + "  to this file will be overwritten.  Please set the is_manual flag for this website\n"
                + "  to be able to make permanent changes to this file.\n"
                + "\n"
                + "  Control Panel: https://secure.aoindustries.com/clientarea/control/httpd/HttpdSiteCP?pkey="+hs.getPKey()+"\n"
                + "\n"
                + "  AOSH: "+AOSHCommand.SET_HTTPD_SITE_IS_MANUAL+" "+hs.getSiteName()+' '+hs.getAOServer().getServer().getHostname()+" true\n"
                + "\n"
                + "  support@aoindustries.com\n"
                + "  (866) 270-6195\n"
                + "-->\n"
            ;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static String getAutoWarning(HttpdSharedTomcat hst) throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, HttpdManager.class, "getAutoWarning(HttpdSharedTomcat)", null);
        try {
            return
                "<!--\n"
                + "  Warning: This file is automatically created by HttpdManager.  Any manual changes\n"
                + "  to this file will be overwritten.  Please set the is_manual flag for this multi-site\n"
                + "  JVM to be able to make permanent changes to this file.\n"
                + "\n"
                + "  Control Panel: https://secure.aoindustries.com/clientarea/control/httpd/HttpdSharedTomcatCP?pkey="+hst.getPKey()+"\n"
                + "\n"
                + "  AOSH: "+AOSHCommand.SET_HTTPD_SHARED_TOMCAT_IS_MANUAL+" "+hst.getName()+' '+hst.getAOServer().getServer().getHostname()+" true\n"
                + "\n"
                + "  support@aoindustries.com\n"
                + "  (866) 270-6195\n"
                + "-->\n"
            ;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
    
    public static void stripFilePrefix(UnixFile uf, String prefix) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "stripFilePrefix(UnixFile,String)", null);
        try {
            // Remove the auto warning if the site has recently become manual
            int prefixLen=prefix.length();
            if(uf.getSize()>=prefixLen) {
                UnixFile newUF=null;
                InputStream in=new BufferedInputStream(uf.getSecureInputStream());
                try {
                    StringBuilder SB=new StringBuilder(prefixLen);
                    int ch;
                    while(SB.length()<prefixLen && (ch=in.read())!=-1) {
                        SB.append((char)ch);
                    }
                    if(SB.toString().equals(prefix)) {
                        newUF=UnixFile.mktemp(uf.getFilename()+'.');
                        OutputStream out=new BufferedOutputStream(
                            newUF.getSecureOutputStream(
                                uf.getUID(),
                                uf.getGID(),
                                uf.getMode(),
                                true
                            )
                        );
                        try {
                            byte[] buff=BufferManager.getBytes();
                            try {
                                int ret;
                                while((ret=in.read(buff, 0, BufferManager.BUFFER_SIZE))!=-1) out.write(buff, 0, ret);
                            } finally {
                                BufferManager.release(buff);
                            }
                        } finally {
			    out.flush();
                            out.close();
                        }
                    }
                } finally {
                    in.close();
                }
                if(newUF!=null) newUF.renameTo(uf);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    
    private static final String getDefaultPhpVersion(int operatingSystemVersion) {
        Profiler.startProfile(Profiler.FAST, HttpdManager.class, "getDefaultPhpVersion(int)", null);
        try {
            //if(operatingSystemVersion==OperatingSystemVersion.REDHAT_7_2_I686) return DEFAULT_REDHAT72_PHP_VERSION;
            //else if(operatingSystemVersion==OperatingSystemVersion.MANDRAKE_9_2_I586) return DEFAULT_MANDRAKE92_PHP_VERSION;
            if(operatingSystemVersion==OperatingSystemVersion.MANDRAKE_10_1_I586) return DEFAULT_MANDRAKE_10_1_PHP_VERSION;
            else throw new RuntimeException("Unsupported OperatingSystemVersion: "+operatingSystemVersion);
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static final String getDefaultJdkVersion(int operatingSystemVersion) {
        Profiler.startProfile(Profiler.FAST, HttpdManager.class, "getDefaultJdkVersion(int)", null);
        try {
            //if(operatingSystemVersion==OperatingSystemVersion.REDHAT_7_2_I686) return DEFAULT_REDHAT_7_2_JDK_VERSION;
            //else if(operatingSystemVersion==OperatingSystemVersion.MANDRAKE_9_2_I586) return DEFAULT_MANDRAKE_9_2_JDK_VERSION;
            if(operatingSystemVersion==OperatingSystemVersion.MANDRAKE_10_1_I586) return DEFAULT_MANDRAKE_10_1_JDK_VERSION;
            else throw new RuntimeException("Unsupported OperatingSystemVersion: "+operatingSystemVersion);
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static final String getDefaultJdkPath(int operatingSystemVersion) {
        Profiler.startProfile(Profiler.FAST, HttpdManager.class, "getDefaultJdkPath(int)", null);
        try {
            //if(operatingSystemVersion==OperatingSystemVersion.REDHAT_7_2_I686) return DEFAULT_REDHAT_7_2_JDK_PATH;
            //else if(operatingSystemVersion==OperatingSystemVersion.MANDRAKE_9_2_I586) return DEFAULT_MANDRAKE_9_2_JDK_PATH;
            if(operatingSystemVersion==OperatingSystemVersion.MANDRAKE_10_1_I586) return DEFAULT_MANDRAKE_10_1_JDK_PATH;
            else throw new RuntimeException("Unsupported OperatingSystemVersion: "+operatingSystemVersion);
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static final String getPhpLib(TechnologyVersion phpVersion) {
        Profiler.startProfile(Profiler.FAST, HttpdManager.class, "getPhpLib(TechnologyVersion)", null);
        try {
            String version=phpVersion.getVersion();
            if(version.length()>0) {
                char first=version.charAt(0);
                if(first=='4') return "libphp4.so";
                if(first=='5') return "libphp5.so";
            }
            throw new RuntimeException("Unsupported PHP version: "+version);
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
    
    private static final void doRebuildLogrotate(
        AOServer aoServer,
        List<File> deleteFileList,
        AOServConnector conn
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "doRebuildLogrotate(AOServer,List<File>,AOServConnector)", null);
        try {
            // The log rotations that exist but are not used will be removed
            String[] list=new File(LOG_ROTATION_DIR).list();
            List<String> logRotationFiles=new SortedArrayList<String>(list.length);
            for(int c=0;c<list.length;c++) logRotationFiles.add(list[c]);

            // Each log file will be only rotated at most once
            Map<String,Object> completedPaths=new HashMap<String,Object>();

            // For each site, build/rebuild the logrotate.d file as necessary and create any necessary log files
            ByteArrayOutputStream byteOut=new ByteArrayOutputStream();
            ChainWriter chainOut=new ChainWriter(byteOut);
            List<HttpdSite> sites=aoServer.getHttpdSites();
            for(int c=0;c<sites.size();c++) {
                HttpdSite site=sites.get(c);

                // Write the new file to RAM first
                byteOut.reset();
                List<HttpdSiteBind> binds=site.getHttpdSiteBinds();
                for(int d=0;d<binds.size();d++) {
                    HttpdSiteBind bind=binds.get(d);
                    writeLogRotateAndMakeLogFiles(site, chainOut, bind, bind.getAccessLog(), deleteFileList, completedPaths);
                    writeLogRotateAndMakeLogFiles(site, chainOut, bind, bind.getErrorLog(), deleteFileList, completedPaths);
                }
                chainOut.flush();
                byte[] newFileContent=byteOut.toByteArray();

                // Write to disk if file missing or doesn't match
                UnixFile logRotation=new UnixFile(LOG_ROTATION_DIR, site.getSiteName());
                if(!logRotation.exists() || !logRotation.contentEquals(newFileContent)) {
                    OutputStream out=logRotation.getSecureOutputStream(
                        UnixFile.ROOT_UID,
                        site.getLinuxServerGroup().getGID().getID(),
                        0640,
                        true
                    );
		    try {
                        out.write(newFileContent);
		    } finally {
			out.close();
		    }
                }

                // Make sure the newly created or replaced log rotation file is not removed
                logRotationFiles.remove(site.getSiteName());
            }

            for(int c=0;c<logRotationFiles.size();c++) deleteFileList.add(new File(LOG_ROTATION_DIR, logRotationFiles.get(c)));
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    
    private static void writeLogRotateAndMakeLogFiles(
        HttpdSite site,
        ChainWriter out,
        HttpdSiteBind siteBind,
        String path,
        List<File> deleteFileList,
        Map<String,Object> completedPaths
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "writeLogRotateAndMakeLogFiles(HttpdSite,ChainWriter,HttpdSiteBind,String,List<File>,Map<String,Object>)", null);
        try {
            // Each unique path is only rotated once
            if(!completedPaths.containsKey(path)) {
                completedPaths.put(path, null);
                
                AOServer aoServer=AOServDaemon.getThisAOServer();
                LinuxServerAccount awstatsLSA=aoServer.getLinuxServerAccount(LinuxAccount.AWSTATS);
                if(awstatsLSA==null) throw new SQLException("Unable to find LinuxServerAccount: "+LinuxAccount.AWSTATS+" on "+aoServer.getServer().getHostname());
                int awstatsUID=awstatsLSA.getUID().getID();
                int lsgGID=site.getLinuxServerGroup().getGID().getID();

                UnixFile logFile=new UnixFile(path);
                UnixFile logFileParent=logFile.getParent();
                
                // Make sure the parent directory exists and has the correct permissions
                if(!logFileParent.exists()) logFileParent.mkdir(true, 0750, awstatsUID, lsgGID);
                else {
                    if(logFileParent.getMode()!=0750) logFileParent.setMode(0750);
                    if(
                        logFileParent.getUID()!=awstatsUID
                        || logFileParent.getGID()!=lsgGID
                    ) logFileParent.chown(awstatsUID, lsgGID);
                }

                // Make sure the log file exists
                if(!logFile.exists()) logFile.getSecureOutputStream(awstatsUID, lsgGID, 0640, false).close();

                // Add to the site log rotation
                out.print(path).print(" {\n    daily\n    rotate 379\n}\n");
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    
    private static String getMajorPhpVersion(String version) {
        int pos=version.indexOf('.');
        return pos<=0?version:version.substring(0, pos);
    }
    
    private static void writeHttpdTomcatParameter(String tomcatVersion, HttpdTomcatParameter parameter, ChainWriter out) {
        out.print("          <Parameter\n"
                + "            name=\"").printXmlAttribute(parameter.getName()).print("\"\n"
                + "            value=\"").printXmlAttribute(parameter.getValue()).print("\"\n"
                + "            override=\"").print(parameter.getOverride()).print("\"\n");
        if(parameter.getDescription()!=null) out.print("            description=\"").printXmlAttribute(parameter.getDescription()).print("\"\n");
        out.print("          />\n");
    }
    
    private static void writeHttpdTomcatDataSource(String tomcatVersion, HttpdTomcatDataSource dataSource, ChainWriter out) throws SQLException {
        if(tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)) {
            out.print("          <Resource\n"
                    + "            name=\"").printXmlAttribute(dataSource.getName()).print("\"\n"
                    + "            auth=\"Container\"\n"
                    + "            type=\"javax.sql.DataSource\"\n"
                    + "          />\n"
                    + "          <ResourceParams name=\"").printXmlAttribute(dataSource.getName()).print("\">\n"
                    + "            <parameter><name>factory</name><value>org.apache.commons.dbcp.BasicDataSourceFactory</value></parameter>\n"
                    + "            <parameter><name>username</name><value>").printXmlBody(dataSource.getUsername()).print("</value></parameter>\n"
                    + "            <parameter><name>password</name><value>").printXmlBody(dataSource.getPassword()).print("</value></parameter>\n"
                    + "            <parameter><name>driverClassName</name><value>").printXmlBody(dataSource.getDriverClassName()).print("</value></parameter>\n"
                    + "            <parameter><name>url</name><value>").printXmlBody(dataSource.getUrl()).print("</value></parameter>\n"
                    + "            <parameter><name>maxActive</name><value>").print(dataSource.getMaxActive()).print("</value></parameter>\n"
                    + "            <parameter><name>maxIdle</name><value>").print(dataSource.getMaxIdle()).print("</value></parameter>\n"
                    + "            <parameter><name>maxWait</name><value>").print(dataSource.getMaxWait()).print("</value></parameter>\n");
            if(dataSource.getValidationQuery()!=null) {
                out.print("            <parameter><name>validationQuery</name><value>").printXmlBody(dataSource.getValidationQuery()).print("</value></parameter>\n");
            }
            out.print("            <parameter><name>removeAbandoned</name><value>true</value></parameter>\n"
                    + "            <parameter><name>removeAbandonedTimeout</name><value>300</value></parameter>\n"
                    + "            <parameter><name>logAbandoned</name><value>true</value></parameter>\n"
                    + "          </ResourceParams>\n");
        } else if(tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_5_5_PREFIX)) {
            out.print("          <Resource\n"
                    + "            name=\"").printXmlAttribute(dataSource.getName()).print("\"\n"
                    + "            auth=\"Container\"\n"
                    + "            type=\"javax.sql.DataSource\"\n"
                    + "            username=\"").printXmlAttribute(dataSource.getUsername()).print("\"\n"
                    + "            password=\"").printXmlAttribute(dataSource.getPassword()).print("\"\n"
                    + "            driverClassName=\"").printXmlAttribute(dataSource.getDriverClassName()).print("\"\n"
                    + "            url=\"").printXmlAttribute(dataSource.getUrl()).print("\"\n"
                    + "            maxActive=\"").print(dataSource.getMaxActive()).print("\"\n"
                    + "            maxIdle=\"").print(dataSource.getMaxIdle()).print("\"\n"
                    + "            maxWait=\"").print(dataSource.getMaxWait()).print("\"\n");
            if(dataSource.getValidationQuery()!=null) {
                out.print("            validationQuery=\"").printXmlAttribute(dataSource.getValidationQuery()).print("\"\n");
            }
            out.print("            removeAbandoned=\"true\"\n"
                    + "            removeAbandonedTimeout=\"300\"\n"
                    + "            logAbandoned=\"true\"\n"
                    + "          />\n");
        } else throw new SQLException("Unexpected Tomcat version: "+tomcatVersion);
    }
}
