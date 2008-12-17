package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOSHCommand;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.HttpdSharedTomcat;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.HttpdSiteBind;
import com.aoindustries.aoserv.client.HttpdSiteURL;
import com.aoindustries.aoserv.client.HttpdTomcatContext;
import com.aoindustries.aoserv.client.HttpdTomcatDataSource;
import com.aoindustries.aoserv.client.HttpdTomcatParameter;
import com.aoindustries.aoserv.client.HttpdTomcatSharedSite;
import com.aoindustries.aoserv.client.HttpdTomcatSite;
import com.aoindustries.aoserv.client.HttpdTomcatVersion;
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.LinuxServerGroup;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.PostgresServer;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.unix.linux.LinuxAccountManager;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.SortedArrayList;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Manages HttpdSharedTomcat configurations.
 * 
 * TODO: Decompose further
 *
 * @author  AO Industries, Inc.
 */
public abstract class HttpdSharedTomcatManager {

    /**
     * Gets the specific manager for one version of shared Tomcat.
     */
    static HttpdSharedTomcatManager getInstance(HttpdSharedTomcat sharedTomcat) throws IOException, SQLException {
        AOServConnector connector=AOServDaemon.getConnector();

        HttpdTomcatVersion htv=sharedTomcat.getHttpdTomcatVersion();
        if(htv.isTomcat3_1(connector)) return new HttpdSharedTomcatManager_3_1(sharedTomcat);
        if(htv.isTomcat3_2_4(connector)) return new HttpdSharedTomcatManager_3_2_4(sharedTomcat);
        if(htv.isTomcat4_1_X(connector)) return new HttpdSharedTomcatManager_4_1_X(sharedTomcat);
        if(htv.isTomcat5_5_X(connector)) return new HttpdSharedTomcatManager_5_5_X(sharedTomcat);
        if(htv.isTomcat6_0_X(connector)) return new HttpdSharedTomcatManager_6_0_X(sharedTomcat);
        throw new SQLException("Unsupported version of shared Tomcat: "+htv.getTechnologyVersion(connector).getVersion()+" on "+sharedTomcat);
    }

    /**
     * Responsible for control of all things in /wwwgroup
     *
     * Only called by the already synchronized <code>HttpdManager.doRebuild()</code> method.
     */
    public static void doRebuild(
	List<File> deleteFileList,
        Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted
    ) throws IOException, SQLException {
        // Get values used in the rest of the method.
        HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
        AOServer aoServer = AOServDaemon.getThisAOServer();
        Stat tempStat = new Stat();

        // The www group directories that exist but are not used will be removed
        UnixFile wwwgroupDirectory = new UnixFile(osConfig.getHttpdSharedTomcatsDirectory());
        String[] list = wwwgroupDirectory.list();
        Set<String> wwwgroupRemoveList = new HashSet<String>(list.length*4/3+1);
        for (String dirname : list) {
            if(
                !dirname.equals("lost+found")
                && !dirname.equals("aquota.user")
            ) {
                wwwgroupRemoveList.add(dirname);
            }
        }

        // Iterate through each shared Tomcat
        for(HttpdSharedTomcat sharedTomcat : aoServer.getHttpdSharedTomcats()) {
            final HttpdSharedTomcatManager manager = getInstance(sharedTomcat);

            // Create and fill in any incomplete installations.
            final String tomcatName = sharedTomcat.getName();
            UnixFile sharedTomcatDirectory = new UnixFile(wwwgroupDirectory, tomcatName, false);
            manager.buildSharedTomcatDirectory(sharedTomcatDirectory, sharedTomcatsNeedingRestarted);
            wwwgroupRemoveList.remove(tomcatName);
        }

        // Stop, disable, and mark files for deletion
        for (String tomcatName : wwwgroupRemoveList) {
            UnixFile removeFile = new UnixFile(wwwgroupDirectory, tomcatName, false);
            // Stop and disable any daemons
            stopAndDisableDaemons(removeFile, tempStat);
            // Only remove the directory when not used by a home directory
            if(!aoServer.isHomeUsed(removeFile.getPath())) deleteFileList.add(removeFile.getFile());
        }
    }
    
    private static void moveMeTODO() {
        /*
         * Get values used in the rest of the loop.
         */
        final HttpdTomcatVersion htv=sharedTomcat.getHttpdTomcatVersion();
        final boolean isTomcat3_1 = htv.isTomcat3_1(conn);
        final boolean isTomcat3_2_4 = htv.isTomcat3_2_4(conn);
        final boolean isTomcat4_1_X = htv.isTomcat4_1_X(conn);
        final boolean isTomcat5_5_X = htv.isTomcat5_5_X(conn);
        final String tomcatDirectory=htv.getInstallDirectory();

        final LinuxServerAccount lsa = sharedTomcat.getLinuxServerAccount();
        final int lsaUID = lsa.getUID().getID();

        final LinuxServerGroup lsg = sharedTomcat.getLinuxServerGroup();
        final int lsgGID = lsg.getGID().getID();

        // Create and fill in the directory if it does not exist or is owned by root.
        final UnixFile wwwGroupDirUF = new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR, sharedTomcat.getName());
        UnixFile workUF = new UnixFile(wwwGroupDirUF, "work", false);
        UnixFile innerWorkUF;
        if(isTomcat4_1_X || isTomcat5_5_X) {
            innerWorkUF=new UnixFile(workUF, "Tomcat-Apache", false);
        } else {
            innerWorkUF=null;
        }

        boolean needRestart=false;
        if (!wwwGroupDirUF.getStat(tempStat).exists() || wwwGroupDirUF.getStat(tempStat).getUID() == UnixFile.ROOT_GID) {

            final String wwwGroupDir = wwwGroupDirUF.getPath();
            final int mgroup = (sharedTomcat.isSecure() ? 0750 : 0770);
            final int mprivate = (sharedTomcat.isSecure() ? 0700 : 0770);

            // Create the /wwwgroup/name/...

            // 001
            if (!wwwGroupDirUF.getStat(tempStat).exists()) wwwGroupDirUF.mkdir();
            wwwGroupDirUF.setMode(sharedTomcat.isSecure() ? 0750 : 0770);
            new UnixFile(wwwGroupDirUF, "bin", false).mkdir().chown(lsaUID, lsgGID).setMode(mgroup);
            new UnixFile(wwwGroupDirUF, "conf", false).mkdir().chown(lsaUID, lsgGID).setMode(sharedTomcat.isSecure() ? 0750 : 0770);
            UnixFile daemonUF = new UnixFile(wwwGroupDirUF, "daemon", false).mkdir().chown(lsaUID, lsgGID).setMode(mprivate);
            if(sharedTomcat.getDisableLog()==null) new UnixFile(daemonUF, "tomcat", false).symLink("../bin/tomcat").chown(lsaUID, lsgGID);
            if(isTomcat4_1_X || isTomcat5_5_X) {
                ln("var/log", wwwGroupDir+"/logs", lsaUID, lsgGID);
                mkdir(wwwGroupDir+"/temp", mprivate, lsa, lsg);
            }
            UnixFile varUF = new UnixFile(wwwGroupDirUF, "var", false).mkdir().chown(lsaUID, lsgGID).setMode(isTomcat3_1 || isTomcat3_2_4 ? mprivate : mgroup);
            new UnixFile(varUF, "log", false).mkdir().chown(lsaUID, lsgGID).setMode(isTomcat3_1 || isTomcat3_2_4 ? mprivate : mgroup);
            new UnixFile(varUF, "run", false).mkdir().chown(lsaUID, lsgGID).setMode(0700);

            workUF.mkdir().chown(lsaUID, lsgGID).setMode(0750);
            if(innerWorkUF!=null) {
                mkdir(innerWorkUF.getPath(), 0750, lsa, lsg);
            }

            PostgresServer postgresServer=aoServer.getPreferredPostgresServer();
            String postgresServerMinorVersion=postgresServer==null?null:postgresServer.getPostgresVersion().getMinorVersion();

            if(isTomcat4_1_X || isTomcat5_5_X) {
                ln("../../.."+tomcatDirectory+"/bin/bootstrap.jar", wwwGroupDir+"/bin/bootstrap.jar", lsaUID, lsgGID);
                ln("../../.."+tomcatDirectory+"/bin/catalina.sh", wwwGroupDir+"/bin/catalina.sh", lsaUID, lsgGID);
                //UnixFile catalinaUF=new UnixFile(wwwGroupDir+"/bin/catalina.sh");
                //new UnixFile(tomcatDirectory+"/bin/catalina.sh").copyTo(catalinaUF, false);
                //catalinaUF.chown(lsaUID, lsgGID).setMode(tomcat.isSecure()?0700:0740);
                ln("../../.."+tomcatDirectory+"/bin/commons-daemon.jar", wwwGroupDir+"/bin/commons-daemon.jar", lsaUID, lsgGID);
                if(isTomcat5_5_X) ln("../../.."+tomcatDirectory+"/bin/commons-logging-api.jar", wwwGroupDir+"/bin/commons-logging-api.jar", lsaUID, lsgGID);
                if(isTomcat4_1_X) ln("../../.."+tomcatDirectory+"/bin/jasper.sh", wwwGroupDir+"/bin/jasper.sh", lsaUID, lsgGID);
                if(isTomcat4_1_X) ln("../../.."+tomcatDirectory+"/bin/jspc.sh", wwwGroupDir+"/bin/jspc.sh", lsaUID, lsgGID);
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

                if(isTomcat4_1_X || isTomcat5_5_X) {
                    out.print(". /etc/profile\n");
                    if(isTomcat5_5_X) {
                        if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                            out.print(". /opt/aoserv-client/scripts/").print(DEFAULT_TOMCAT_5_JDK_VERSION).print(".sh\n");
                        } else {
                            out.print(". /usr/aoserv/etc/").print(DEFAULT_TOMCAT_5_JDK_VERSION).print(".sh\n");
                        }
                    } else {
                        // Note: Pre Tomcat 5.5 not installed on RedHat ES4
                        out.print(". /usr/aoserv/etc/").print(getDefaultJdkVersion(osv)).print(".sh\n");
                    }
                    String defaultPhpVersion = getDefaultPhpVersion(osv);
                    if(defaultPhpVersion!=null) {
                        if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                            out.print(". /opt/aoserv-client/scripts/php-").print(defaultPhpVersion).print(".sh\n");
                        } else {
                            out.print(". /usr/aoserv/etc/php-").print(defaultPhpVersion).print(".sh\n");
                        }
                    }
                    if(postgresServerMinorVersion!=null) {
                        if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                            out.print(". /opt/aoserv-client/scripts/postgresql-").print(postgresServerMinorVersion).print(".sh\n");
                        } else {
                            out.print(". /usr/aoserv/etc/postgresql-").print(postgresServerMinorVersion).print(".sh\n");
                        }
                    }
                    if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                        out.print(". /opt/aoserv-client/scripts/aoserv-client.sh\n");
                    } else {
                        out.print(". /usr/aoserv/etc/aoserv.sh\n");
                    }
                    out.print("\n"
                              + "umask 002\n"
                              + "export DISPLAY=:0.0\n"
                              + "\n"
                              + "export CATALINA_BASE=\"").print(wwwGroupDir).print("\"\n"
                              + "export CATALINA_HOME=\"").print(wwwGroupDir).print("\"\n"
                              + "export CATALINA_TEMP=\"").print(wwwGroupDir).print("/temp\"\n"
                    );
                } else if (isTomcat3_1 || isTomcat3_2_4) {
                    out.print(". /etc/profile\n");
                    if(sharedTomcat.isSecure()) {
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
                            + ". /usr/aoserv/etc/xalan-1.2.d02.sh\n");
                    String defaultPhpVersion = getDefaultPhpVersion(osv);
                    if(defaultPhpVersion!=null) {
                        if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                            out.print(". /opt/aoserv-client/scripts/php-").print(defaultPhpVersion).print(".sh\n");
                        } else {
                            out.print(". /usr/aoserv/etc/php-").print(defaultPhpVersion).print(".sh\n");
                        }
                    }
                    if(postgresServerMinorVersion!=null) out.print(". /usr/aoserv/etc/postgresql-").print(postgresServerMinorVersion).print(".sh\n");
                    out.print(". /usr/aoserv/etc/profile\n"
                            + ". /usr/aoserv/etc/fop-0.15.0.sh\n");
                } else throw new IllegalArgumentException("Unknown version of Tomcat: " + htv.getTechnologyVersion(conn).getVersion());

                out.print("\n"
                        + "export PATH=${PATH}:").print(wwwGroupDir).print("/bin\n"
                        + "\n"
                        + "export JAVA_OPTS='-server -Djava.awt.headless=true'\n"
                        + "\n");
                if(isTomcat3_1 || isTomcat3_2_4) {
                    out.print("# Add site group classes\n"
                            + "CLASSPATH=${CLASSPATH}:").print(wwwGroupDir).print("/classes\n"
                            + "for i in ").print(wwwGroupDir).print("/lib/* ; do\n"
                            + "    if [ -f $i ]; then\n"
                            + "        CLASSPATH=${CLASSPATH}:$i\n"
                            + "    fi\n"
                            + "done\n"
                            + "\n");
                }
                out.print(". "+HttpdSharedTomcat.WWW_GROUP_DIR+'/').print(sharedTomcat.getName()).print("/bin/profile.sites\n"
                        + "\n"
                        + "for SITE in $SITES ; do\n"
                        + "    export PATH=${PATH}:"+HttpdSite.WWW_DIRECTORY+"/${SITE}/bin\n");
                if(isTomcat3_1 || isTomcat3_2_4) {
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
            if(isTomcat4_1_X || isTomcat5_5_X) {
                out.print("            ${TOMCAT_HOME}/bin/catalina.sh stop 2>&1 >>${TOMCAT_HOME}/var/log/tomcat_err\n");
            } else if(sharedTomcat.isSecure()) {
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
            if(isTomcat4_1_X || isTomcat5_5_X) {
                if(sharedTomcat.isSecure()) {
                    out.print("            ${TOMCAT_HOME}/bin/build_catalina_policy >${TOMCAT_HOME}/conf/catalina.policy\n"
                            + "            chmod 600 ${TOMCAT_HOME}/conf/catalina.policy\n"
                            + "            mv -f ${TOMCAT_HOME}/var/log/tomcat_err ${TOMCAT_HOME}/var/log/tomcat_err.old\n"
                            + "            ${TOMCAT_HOME}/bin/catalina.sh run -security >&${TOMCAT_HOME}/var/log/tomcat_err &\n");
                } else {
                    out.print("            mv -f ${TOMCAT_HOME}/var/log/tomcat_err ${TOMCAT_HOME}/var/log/tomcat_err.old\n"
                            + "            ${TOMCAT_HOME}/bin/catalina.sh run >&${TOMCAT_HOME}/var/log/tomcat_err &\n");
                }
            } else {
                if(sharedTomcat.isSecure()) out.print("            java com.aoindustries.apache.tomcat.SecureVirtualTomcat start $SITES &>var/log/servlet_err &\n");
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
                out.close();
            }

            if(isTomcat4_1_X || isTomcat5_5_X) {
                ln("../../.."+tomcatDirectory+"/bin/setclasspath.sh", wwwGroupDir+"/bin/setclasspath.sh", lsaUID, lsgGID);

                UnixFile shutdown=new UnixFile(wwwGroupDir+"/bin/shutdown.sh");
                out=new ChainWriter(shutdown.getSecureOutputStream(lsaUID, lsgGID, 0700, true));
                try {
                    out.print("#!/bin/sh\n"
                              + "exec ").print(wwwGroupDir).print("/bin/tomcat stop\n");
                } finally {
                    out.close();
                }

                UnixFile startup=new UnixFile(wwwGroupDir+"/bin/startup.sh");
                out=new ChainWriter(startup.getSecureOutputStream(lsaUID, lsgGID, 0700, true));
                try {
                    out.print("#!/bin/sh\n"
                              + "exec ").print(wwwGroupDir).print("/bin/tomcat start\n");
                } finally {
                    out.close();
                }

                if(isTomcat4_1_X) ln("../../.."+tomcatDirectory+"/bin/tomcat-jni.jar", wwwGroupDir+"/bin/tomcat-jni.jar", lsaUID, lsgGID);
                if(isTomcat5_5_X) ln("../../.."+tomcatDirectory+"/bin/tomcat-juli.jar", wwwGroupDir+"/bin/tomcat-juli.jar", lsaUID, lsgGID);
                if(isTomcat4_1_X || isTomcat5_5_X) ln("../../.."+tomcatDirectory+"/bin/tool-wrapper.sh", wwwGroupDir+"/bin/tool-wrapper.sh", lsaUID, lsgGID);
                if(isTomcat5_5_X) ln("../../.."+tomcatDirectory+"/bin/version.sh", wwwGroupDir+"/bin/version.sh", lsaUID, lsgGID);

                // The classes directory
                if(isTomcat3_1 || isTomcat3_2_4) new UnixFile(wwwGroupDirUF, "classes", false).mkdir().chown(lsaUID, lsgGID).setMode(mgroup);

                // Create the common directory and all contents
                mkdir(wwwGroupDir+"/common", sharedTomcat.isSecure()?0750:0770, lsa, lsg);
                mkdir(wwwGroupDir+"/common/classes", sharedTomcat.isSecure()?0750:0770, lsa, lsg);
                mkdir(wwwGroupDir+"/common/endorsed", sharedTomcat.isSecure()?0750:0770, lsa, lsg);
                lnAll("../../../.."+tomcatDirectory+"/common/endorsed/", wwwGroupDir+"/common/endorsed/", lsaUID, lsgGID);
                mkdir(wwwGroupDir+"/common/i18n", sharedTomcat.isSecure()?0750:0770, lsa, lsg);
                if(isTomcat5_5_X) lnAll("../../../.."+tomcatDirectory+"/common/i18n/", wwwGroupDir+"/common/i18n/", lsaUID, lsgGID);
                mkdir(wwwGroupDir+"/common/lib", sharedTomcat.isSecure()?0750:0770, lsa, lsg);
                lnAll("../../../.."+tomcatDirectory+"/common/lib/", wwwGroupDir+"/common/lib/", lsaUID, lsgGID);

                if(postgresServerMinorVersion!=null) {
                    if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                        ln("../../../../usr/postgresql/"+postgresServerMinorVersion+"/share/java/postgresql.jar", wwwGroupDir+"/common/lib/postgresql.jar", lsaUID, lsgGID);
                    } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                        ln("../../../../opt/postgresql-"+postgresServerMinorVersion+"/share/java/postgresql.jar", wwwGroupDir+"/common/lib/postgresql.jar", lsaUID, lsgGID);
                    } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                }
                if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                    ln("../../../../usr/mysql-connector-java/3.1.12/mysql-connector-java-3.1.12-bin.jar", wwwGroupDir+"/common/lib/mysql-connector-java-3.1.12-bin.jar", lsaUID, lsgGID);
                } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                    ln("../../../../opt/mysql-connector-java-3.1.12/mysql-connector-java-3.1.12-bin.jar", wwwGroupDir+"/common/lib/mysql-connector-java-3.1.12-bin.jar", lsaUID, lsgGID);
                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

                // Write the conf/catalina.policy file
                if(!sharedTomcat.isSecure()) {
                    {
                        UnixFile cp=new UnixFile(wwwGroupDir+"/conf/catalina.policy");
                        new UnixFile(tomcatDirectory+"/conf/catalina.policy").copyTo(cp, false);
                        cp.chown(lsaUID, lsgGID).setMode(0660);
                    }
                    if(isTomcat5_5_X) {
                        UnixFile cp=new UnixFile(wwwGroupDir+"/conf/catalina.properties");
                        new UnixFile(tomcatDirectory+"/conf/catalina.properties").copyTo(cp, false);
                        cp.chown(lsaUID, lsgGID).setMode(0660);
                    }
                    if(isTomcat5_5_X) {
                        UnixFile cp=new UnixFile(wwwGroupDir+"/conf/context.xml");
                        new UnixFile(tomcatDirectory+"/conf/context.xml").copyTo(cp, false);
                        cp.chown(lsaUID, lsgGID).setMode(0660);
                    }
                    if(isTomcat5_5_X) {
                        UnixFile cp=new UnixFile(wwwGroupDir+"/conf/logging.properties");
                        new UnixFile(tomcatDirectory+"/conf/logging.properties").copyTo(cp, false);
                        cp.chown(lsaUID, lsgGID).setMode(0660);
                    }
                }

                // Create the tomcat-users.xml file
                UnixFile tuUF=new UnixFile(wwwGroupDir+"/conf/tomcat-users.xml");
                new UnixFile(tomcatDirectory+"/conf/tomcat-users.xml").copyTo(tuUF, false);
                tuUF.chown(lsaUID, lsgGID).setMode(sharedTomcat.isSecure()?0640:0660);

                // Create the web.xml file.
                UnixFile webUF=new UnixFile(wwwGroupDir+"/conf/web.xml");
                new UnixFile(tomcatDirectory+"/conf/web.xml").copyTo(webUF, false);
                webUF.chown(lsaUID, lsgGID).setMode(sharedTomcat.isSecure()?0640:0660);

                // Create /lib
                if(isTomcat3_1 || isTomcat3_2_4) {
                    new UnixFile(wwwGroupDirUF, "lib", false).mkdir().chown(lsaUID, lsgGID).setMode(mgroup);
                    lnAll("../../.."+tomcatDirectory+"/lib/", wwwGroupDir+"/lib/", lsaUID, lsgGID);
                    ln("../../.."+tomcatDirectory+"/lib/jasper-runtime.jar", wwwGroupDir+"/lib/jasper-runtime.jar", lsaUID, lsgGID);
                    if(postgresServerMinorVersion!=null) {
                        if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                            ln("../../../usr/postgresql/"+postgresServerMinorVersion+"/share/java/postgresql.jar", wwwGroupDir+"/lib/postgresql.jar", lsaUID, lsgGID);
                        } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                            ln("../../../opt/postgresql-"+postgresServerMinorVersion+"/share/java/postgresql.jar", wwwGroupDir+"/lib/postgresql.jar", lsaUID, lsgGID);
                        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                    }
                    if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                        ln("../../../usr/mysql-connector-java/3.1.12/mysql-connector-java-3.1.12-bin.jar", wwwGroupDir+"/lib/mysql-connector-java-3.1.12-bin.jar", lsaUID, lsgGID);
                    } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                        ln("../../../../opt/mysql-connector-java-3.1.12/mysql-connector-java-3.1.12-bin.jar", wwwGroupDir+"/common/lib/mysql-connector-java-3.1.12-bin.jar", lsaUID, lsgGID);
                    }
                    else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                }
                mkdir(wwwGroupDir+"/server", sharedTomcat.isSecure()?0750:0770, lsa, lsg);
                mkdir(wwwGroupDir+"/server/classes", sharedTomcat.isSecure()?0750:0770, lsa, lsg);
                mkdir(wwwGroupDir+"/server/lib", sharedTomcat.isSecure()?0750:0770, lsa, lsg);
                lnAll("../../../.."+tomcatDirectory+"/server/lib/", wwwGroupDir+"/server/lib/", lsaUID, lsgGID);

                if(isTomcat4_1_X || isTomcat5_5_X) mkdir(wwwGroupDir+"/server/webapps", sharedTomcat.isSecure()?0750:0770, lsa, lsg);
            }

            // The shared directory
            if(isTomcat4_1_X || isTomcat5_5_X) {
                mkdir(wwwGroupDir+"/shared", 0770, lsa, lsg);
                mkdir(wwwGroupDir+"/shared/classes", 0770, lsa, lsg);
                mkdir(wwwGroupDir+"/shared/lib", 0770, lsa, lsg);
            }

            //  008
            if(isTomcat3_1 || isTomcat3_2_4) {
                UnixFile servErrUF = new UnixFile(varUF, "log/servlet_err", false);
                servErrUF.getSecureOutputStream(lsaUID, lsgGID, 0640, false).close();
            }

            // Set the ownership to avoid future rebuilds of this directory
            wwwGroupDirUF.chown(lsaUID, lsgGID);

            needRestart=true;
        }

        // always rebuild profile.sites file
        UnixFile newSitesFileUF = new UnixFile(wwwGroupDirUF, "bin/profile.sites.new", false);
        ChainWriter out = new ChainWriter(
            new BufferedOutputStream(
                newSitesFileUF.getSecureOutputStream(lsaUID, lsgGID, 0750, true)
            )
        );
        List<HttpdTomcatSharedSite> sites = sharedTomcat.getHttpdTomcatSharedSites();
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
            out.close();
        }
        // flag as needing a restart if this file is different than any existing
        UnixFile sitesFile = new UnixFile(wwwGroupDirUF, "bin/profile.sites", false);
        if(!sitesFile.getStat(tempStat).exists() || !newSitesFileUF.contentEquals(sitesFile)) {
            needRestart=true;
            UnixFile backupFile=new UnixFile(wwwGroupDirUF, "bin/profile.sites.old", false);
            if(sitesFile.getStat(tempStat).exists()) sitesFile.renameTo(backupFile);
            newSitesFileUF.renameTo(sitesFile);
        } else newSitesFileUF.delete();

        if((isTomcat4_1_X || isTomcat5_5_X) && sharedTomcat.isSecure()) {
            UnixFile buildPolicy=new UnixFile(wwwGroupDirUF, "bin/build_catalina_policy", false);
            if(!sharedTomcat.isManual() || buildPolicy.getStat(tempStat).exists()) {
                // always rebuild the bin/build_catalina_policy script for secured and auto JVMs
                UnixFile newBuildPolicy=new UnixFile(wwwGroupDirUF, "bin/build_catalina_policy.new", false);
                out=new ChainWriter(
                    new BufferedOutputStream(
                        newBuildPolicy.getSecureOutputStream(lsaUID, lsgGID, 0700, true)
                    )
                );
                try {
                    out.print("#!/bin/sh\n"
                              + ". /usr/aoserv/sbin/build_catalina_policy\n"
                              + "buildCatalinaPolicy '"+HttpdSharedTomcat.WWW_GROUP_DIR+'/').print(sharedTomcat.getName()).print("' ")
                        .print(sharedTomcat.getTomcat4Worker().getNetBind().getPort().getPort()).print(' ')
                        .print(sharedTomcat.getTomcat4ShutdownPort().getPort().getPort());
                    for (int j = 0; j< sites.size(); j++) {
                        HttpdSite hs=sites.get(j).getHttpdTomcatSite().getHttpdSite();
                        if(hs.getDisableLog()==null) out.print(" '").print(hs.getSiteName()).print('\'');
                    }
                    out.print('\n');
                } finally {
                    out.close();
                }

                // flag as needing a restart if this file is different than any existing
                if(!buildPolicy.getStat(tempStat).exists() || !newBuildPolicy.contentEquals(buildPolicy)) {
                    needRestart=true;
                    UnixFile backupBuildPolicy = new UnixFile(wwwGroupDirUF, "bin/build_catalina_policy.old", false);
                    if(buildPolicy.getStat(tempStat).exists()) buildPolicy.renameTo(backupBuildPolicy);
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
                String subwork = isTomcat4_1_X || isTomcat5_5_X ? hs.getPrimaryHttpdSiteURL().getHostname() : hs.getSiteName();
                workFiles.remove(subwork);
                UnixFile workDir = new UnixFile((innerWorkUF==null?workUF:innerWorkUF), subwork, false);
                if (!workDir.getStat(tempStat).exists()) {
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

        // Rebuild the server.xml for Tomcat 4 and Tomcat 5 JVMs
        if(isTomcat4_1_X || isTomcat5_5_X) {
            String autoWarning=getAutoWarningXml(sharedTomcat);
            String confServerXML=HttpdSharedTomcat.WWW_GROUP_DIR+'/'+sharedTomcat.getName()+"/conf/server.xml";
            UnixFile confServerXMLUF=new UnixFile(confServerXML);
            if(!sharedTomcat.isManual() || !confServerXMLUF.getStat(tempStat).exists()) {
                String newConfServerXML=HttpdSharedTomcat.WWW_GROUP_DIR+'/'+sharedTomcat.getName()+"/conf/server.xml.new";
                UnixFile newConfServerXMLUF=new UnixFile(newConfServerXML);
                out=new ChainWriter(
                    new BufferedOutputStream(
                        newConfServerXMLUF.getSecureOutputStream(lsaUID, lsgGID, sharedTomcat.isSecure()?0600:0660, true)
                    )
                );
                try {
                    HttpdWorker hw=sharedTomcat.getTomcat4Worker();
                    if(!sharedTomcat.isManual()) out.print(autoWarning);
                    NetBind shutdownPort = sharedTomcat.getTomcat4ShutdownPort();
                    if(shutdownPort==null) throw new SQLException("Unable to find shutdown key for HttpdSharedTomcat: "+sharedTomcat);
                    String shutdownKey=sharedTomcat.getTomcat4ShutdownKey();
                    if(shutdownKey==null) throw new SQLException("Unable to find shutdown key for HttpdSharedTomcat: "+sharedTomcat);
                    out.print("<Server port=\"").print(shutdownPort.getPort().getPort()).print("\" shutdown=\"").print(shutdownKey).print("\" debug=\"0\">\n");
                    if(isTomcat5_5_X) {
                        out.print("  <GlobalNamingResources>\n"
                                + "    <Resource name=\"UserDatabase\" auth=\"Container\"\n"
                                + "              type=\"org.apache.catalina.UserDatabase\"\n"
                                + "       description=\"User database that can be updated and saved\"\n"
                                + "           factory=\"org.apache.catalina.users.MemoryUserDatabaseFactory\"\n"
                                + "          pathname=\"conf/tomcat-users.xml\" />\n"
                                + "   </GlobalNamingResources>\n");
                    } else if(isTomcat4_1_X) {
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
                    out.print("  <Service name=\"").print(isTomcat5_5_X ? "Catalina" : "Tomcat-Apache").print("\">\n"
                            + "    <Connector\n");
                            //+ "      className=\"org.apache.coyote.tomcat4.CoyoteConnector\"\n"
                            //+ "      port=\"").print(hw.getNetBind().getPort().getPort()).print("\"\n"
                            //+ "      minProcessors=\"2\"\n"
                            //+ "      maxProcessors=\"200\"\n"
                            //+ "      enableLookups=\"true\"\n"
                            //+ "      redirectPort=\"443\"\n"
                            //+ "      acceptCount=\"10\"\n"
                            //+ "      debug=\"0\"\n"
                            //+ "      connectionTimeout=\"20000\"\n"
                            //+ "      useURIValidationHack=\"false\"\n"
                            //+ "      protocolHandlerClassName=\"org.apache.jk.server.JkCoyoteHandler\"\n"
                    if (isTomcat4_1_X) {
                        out.print("      className=\"org.apache.ajp.tomcat4.Ajp13Connector\"\n");
                    }
                    out.print("      port=\"").print(hw.getNetBind().getPort().getPort()).print("\"\n");
                    if(isTomcat5_5_X) out.print("      enableLookups=\"false\"\n");
                    out.print("      minProcessors=\"2\"\n"
                            + "      maxProcessors=\"200\"\n"
                            + "      address=\""+IPAddress.LOOPBACK_IP+"\"\n"
                            + "      acceptCount=\"10\"\n"
                            + "      debug=\"0\"\n"
                            + "      protocol=\"AJP/1.3\"\n"
                            + "    />\n"
                            + "    <Engine name=\"").print(isTomcat5_5_X ? "Catalina" : "Tomcat-Apache").print("\" defaultHost=\"localhost\" debug=\"0\">\n");
                    if(isTomcat5_5_X) {
                        out.print("      <Realm className=\"org.apache.catalina.realm.UserDatabaseRealm\"\n"
                                + "          resourceName=\"UserDatabase\" />\"\n");
                    } else if(isTomcat4_1_X) {
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
                            if(isTomcat4_1_X || isTomcat5_5_X) out.print("        autoDeploy=\"true\"\n");
                            if(isTomcat5_5_X) {
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
                            if (isTomcat4_1_X) {
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
                                        writeHttpdTomcatParameter(htv, conn, parameter, out);
                                    }
                                    // Data Sources
                                    for(HttpdTomcatDataSource dataSource : dataSources) {
                                        writeHttpdTomcatDataSource(htv, conn, dataSource, out);
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
                    out.close();
                }

                // Must restart JVM if this file has changed
                if(
                    !confServerXMLUF.getStat(tempStat).exists()
                    || !newConfServerXMLUF.contentEquals(confServerXMLUF)
                ) {
                    needRestart=true;
                    newConfServerXMLUF.renameTo(confServerXMLUF);
                } else newConfServerXMLUF.delete();
            } else {
                try {
                    stripFilePrefix(
                        confServerXMLUF,
                        autoWarning,
                        tempStat
                    );
                } catch(IOException err) {
                    // Errors OK because this is done in manual mode and they might have symbolic linked stuff
                }
            }
        }

        // Start if needed
        if(needRestart && sharedTomcat.getDisableLog()==null) sharedTomcatsNeedingRestarted.add(sharedTomcat);
    }

    /**
     * Stops any daemons that should not be running.
     * Restarts any sites that need restarted.
     * Starts any daemons that should be running.
     * 
     * Makes calls with a one-minute time-out.
     * Logs errors on calls as warnings, continues to next site.
     *
     * Only called by the already synchronized <code>HttpdManager.doRebuild()</code> method.
     */
    public static void stopStartAndRestart(Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted) throws IOException, SQLException {
        for(HttpdSharedTomcat sharedTomcat : AOServDaemon.getThisAOServer().getHttpdSharedTomcats()) {
            final HttpdSharedTomcatManager manager = getInstance(sharedTomcat);

            Callable<Object> commandCallable;
            if(sharedTomcat.getDisableLog()==null && !sharedTomcat.getHttpdTomcatSharedSites().isEmpty()) {
                // Enabled and has sites, start or restart
                if(sharedTomcatsNeedingRestarted.contains(sharedTomcat)) {
                    commandCallable = new Callable<Object>() {
                        public Object call() {
                            manager.restart();
                            return null;
                        }
                    };
                } else {
                    commandCallable = new Callable<Object>() {
                        public Object call() {
                            manager.start();
                            return null;
                        }
                    };
                }
            } else {
                // Disabled or has no sites, can only stop if needed
                commandCallable = new Callable<Object>() {
                    public Object call() {
                        manager.stop();
                        return null;
                    }
                };
            }
            try {
                Future commandFuture = AOServDaemon.executorService.submit(commandCallable);
                commandFuture.get(1, TimeUnit.MINUTES);
            } catch(InterruptedException err) {
                AOServDaemon.reportWarning(err, null);
            } catch(ExecutionException err) {
                AOServDaemon.reportWarning(err, null);
            } catch(TimeoutException err) {
                AOServDaemon.reportWarning(err, null);
            }
        }
    }

    /**
     * Only called by the already synchronized <code>HttpdManager.doRebuild()</code> method.
     */
    private static void moveMeTODO2(Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted) throws IOException, SQLException {
        Stat tempStat = new Stat();
        AOServer aoServer = AOServDaemon.getThisAOServer();
        List<HttpdSharedTomcat> hsts=aoServer.getHttpdSharedTomcats();
        for(int c=0;c<hsts.size();c++) {
            HttpdSharedTomcat hst=hsts.get(c);
            String tomcatName=hst.getName();
            UnixFile pidUF=new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcatName+"/var/run/tomcat.pid");
            UnixFile daemonUF=new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcatName+"/daemon/tomcat");
            if(hst.getDisableLog()==null) {
                // Enabled, make sure running and auto
                if(sharedTomcatsNeedingRestarted.contains(hst) || !pidUF.getStat(tempStat).exists()) startSharedTomcat(hst);
                if(!daemonUF.getStat(tempStat).exists()) enableSharedTomcat(hst, tempStat);
            } else {
                // Disabled, make sure stopped and not auto
                if(daemonUF.getStat(tempStat).exists()) disableSharedTomcat(hst, tempStat);
                if(pidUF.getStat(tempStat).exists()) stopSharedTomcat(hst, tempStat);
            }
        }
    }

    /**
     * @see  HttpdSiteManager#stopAndDisableDaemons
     */
    private static void stopAndDisableDaemons(UnixFile sharedTomcatDirectory, Stat tempStat) throws IOException, SQLException {
        HttpdSiteManager.stopAndDisableDaemons(sharedTomcatDirectory, tempStat);
    }

    /**
     * TODO: Is this used?
     */
    static void startSharedTomcat(HttpdSharedTomcat hst) throws IOException, SQLException {
        LinuxServerAccount lsa=hst.getLinuxServerAccount();
        AOServDaemon.suexec(
            lsa.getLinuxAccount().getUsername().getUsername(),
            HttpdSharedTomcat.WWW_GROUP_DIR+'/'+hst.getName()+"/bin/tomcat start",
            0
        );
    }

    /**
     * TODO: Is this used?
     */
    static void stopSharedTomcat(HttpdSharedTomcat hst, Stat tempStat) throws IOException, SQLException {
        stopSharedTomcat(hst.getLinuxServerAccount(), hst.getName(), tempStat);
    }
    
    /**
     * TODO: Is this used?
     */
    private static void stopSharedTomcat(String tomcatName, Stat tempStat) throws IOException, SQLException {
        UnixFile uf=new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcatName+"/daemon");
        uf.getStat(tempStat);
        if(tempStat.exists()) {
            int uid=tempStat.getUID();
            LinuxServerAccount lsa=AOServDaemon.getThisAOServer().getLinuxServerAccount(uid);
            if(lsa!=null) {
                stopSharedTomcat(lsa, tomcatName, tempStat);
            }
        }
    }

    /**
     * TODO: Is this used?
     */
    private static void stopSharedTomcat(LinuxServerAccount lsa, String tomcatName, Stat tempStat) throws IOException, SQLException {
        UnixFile pidUF=new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcatName+"/var/run/tomcat.pid");
        if(pidUF.getStat(tempStat).exists()) {
            AOServDaemon.suexec(
                lsa.getLinuxAccount().getUsername().getUsername(),
                HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcatName+"/bin/tomcat stop",
                0
            );
        }
    }

    /**
     * TODO: Is this used?
     */
    private static void enableSharedTomcat(HttpdSharedTomcat hst, Stat tempStat) throws IOException, SQLException {
        UnixFile uf=new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR+'/'+hst.getName()+"/daemon/tomcat");
        if(!uf.getStat(tempStat).exists()) uf.symLink("../bin/tomcat").chown(
            hst.getLinuxServerAccount().getUID().getID(),
            hst.getLinuxServerGroup().getGID().getID()
        );
    }

    /**
     * TODO: Is this used?
     */
    private static void disableSharedTomcat(HttpdSharedTomcat hst, Stat tempStat) throws IOException {
        disableSharedTomcat(hst.getName(), tempStat);
    }

    /**
     * TODO: Is this used?
     */
    private static void disableSharedTomcat(String tomcatName, Stat tempStat) throws IOException {
        UnixFile uf=new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcatName+"/daemon/tomcat");
        if(uf.getStat(tempStat).exists()) uf.delete();
    }

    final protected HttpdSharedTomcat sharedTomcat;

    HttpdSharedTomcatManager(HttpdSharedTomcat sharedTomcat) {
        this.sharedTomcat = sharedTomcat;
    }
    
    /**
     * Gets the auto-mode warning for this website for use in XML files.  This
     * may be used on any config files that a user would be tempted to change
     * directly.
     */
    String getAutoWarningXml() throws IOException, SQLException {
        return
            "<!--\n"
            + "  Warning: This file is automatically created by HttpdManager.  Any manual changes\n"
            + "  to this file will be overwritten.  Please set the is_manual flag for this multi-site\n"
            + "  JVM to be able to make permanent changes to this file.\n"
            + "\n"
            + "  Control Panel: https://www.aoindustries.com/clientarea/control/httpd/HttpdSharedTomcatCP.ao?pkey="+sharedTomcat.getPkey()+"\n"
            + "\n"
            + "  AOSH: "+AOSHCommand.SET_HTTPD_SHARED_TOMCAT_IS_MANUAL+" "+sharedTomcat.getName()+' '+sharedTomcat.getAOServer().getHostname()+" true\n"
            + "\n"
            + "  support@aoindustries.com\n"
            + "  (866) 270-6195\n"
            + "-->\n"
        ;
    }

    /**
     * Gets the auto-mode warning using Unix-style comments (#).  This
     * may be used on any config files that a user would be tempted to change
     * directly.
     */
    String getAutoWarningUnix() throws IOException, SQLException {
        return
            "#\n"
            + "# Warning: This file is automatically created by HttpdManager.  Any manual changes\n"
            + "# to this file will be overwritten.  Please set the is_manual flag for this multi-site\n"
            + "# JVM to be able to make permanent changes to this file.\n"
            + "#\n"
            + "# Control Panel: https://www.aoindustries.com/clientarea/control/httpd/HttpdSharedTomcatCP.ao?pkey="+sharedTomcat.getPkey()+"\n"
            + "#\n"
            + "# AOSH: "+AOSHCommand.SET_HTTPD_SHARED_TOMCAT_IS_MANUAL+" "+sharedTomcat.getName()+' '+sharedTomcat.getAOServer().getHostname()+" true\n"
            + "#\n"
            + "# support@aoindustries.com\n"
            + "# (866) 270-6195\n"
            + "#\n"
        ;
    }

    /**
     * (Re)builds the shared tomcat directory, from scratch if it doesn't exist.
     * Creates, recreates, or removes resources as necessary.
     * Also performs an automatic upgrade of resources if appropriate for the shared tomcat.
     * Also reconfigures any config files within the directory if appropriate for the shared tomcat type and settings.
     * If this shared tomcat or other shared tomcats needs to be restarted due to changes in the files, add to <code>sharedTomcatsNeedingRestarted</code>.
     * Any files under sharedTomcatDirectory that need to be updated to enable/disable this site should be changed.
     * Actual process start/stop will be performed later in <code>disableEnableAndRestart</code>.
     * 
     * <ol>
     *   <li>If <code>sharedTomcatDirectory</code> doesn't exist, create it as root with mode 0700</li>
     *   <li>If <code>sharedTomcatDirectory</code> owned by root, do full pass (this implies manual=false regardless of setting)</li>
     *   <li>Otherwise, make necessary config changes or upgrades while adhering to the manual flag</li>
     * </ol>
     */
    protected abstract void buildSharedTomcatDirectory(UnixFile sharedTomcatDirectory, Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted) throws IOException, SQLException;

    /**
     * Stops all processes for this shared tomcat if it is running.
     */
    public void stop() {
        // TODO
    }

    /**
     * Starts all processes for this shared tomcat if it is not running.
     */
    public void start() {
        // TODO
    }

    /**
     * Restarts all processes for this shared tomcat if running.
     * If not already running, starts the services.
     */
    public void restart() {
        // TODO
    }

    abstract TomcatCommon getTomcatCommon();
}
