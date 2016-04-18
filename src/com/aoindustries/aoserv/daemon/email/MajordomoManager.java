/*
 * Copyright 2003-2013, 2015, 2016 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.email;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.LinuxGroup;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.LinuxServerGroup;
import com.aoindustries.aoserv.client.MajordomoList;
import com.aoindustries.aoserv.client.MajordomoServer;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.validator.DomainName;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * @author  AO Industries, Inc.
 */
final public class MajordomoManager extends BuilderThread {

	private static MajordomoManager majordomoManager;

	private MajordomoManager() {
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			AOServer aoServer=AOServDaemon.getThisAOServer();
			AOServConnector connector=AOServDaemon.getConnector();

			int osv=aoServer.getServer().getOperatingSystemVersion().getPkey();
			if(
				osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
				&& osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);

			// Reused during processing below
			final UnixFile serversUF=new UnixFile(MajordomoServer.MAJORDOMO_SERVER_DIRECTORY);

			synchronized(rebuildLock) {
				final List<MajordomoServer> mss=aoServer.getMajordomoServers();

				if(!mss.isEmpty()) {
					// Install package if needed
					PackageManager.installPackage(PackageManager.PackageName.MAJORDOMO);

					// Create the directory if needed
					// Will have been created by RPM: if(!serversUF.getStat().exists()) serversUF.mkdir(false, 0755, UnixFile.ROOT_UID, UnixFile.ROOT_GID);
				}

				// Resolve the GID for "mail"
				int mailGID;
				{
					LinuxGroup mailLG=connector.getLinuxGroups().get(LinuxGroup.MAIL);
					if(mailLG==null) throw new SQLException("Unable to find LinuxGroup: "+LinuxGroup.MAIL);
					LinuxServerGroup mailLSG=mailLG.getLinuxServerGroup(aoServer);
					if(mailLSG==null) throw new SQLException("Unable to find LinuxServerGroup: "+LinuxGroup.MAIL+" on "+aoServer.getHostname());
					mailGID=mailLSG.getGid().getID();
				}

				// A list of things to be deleted is maintained
				List<File> deleteFileList=new ArrayList<>();

				// Get the list of all things in /etc/mail/majordomo
				String[] list = serversUF.list();
				Set<DomainName> existingServers;
				existingServers = new HashSet<>(list==null ? 16 : (list.length*4/3+1));
				if(list!=null) for(String filename : list) existingServers.add(DomainName.valueOf(filename));

				// Take care of all servers
				for(MajordomoServer ms : mss) {
					String version=ms.getVersion().getVersion();
					DomainName domain=ms.getDomain().getDomain();
					// Make sure it won't be deleted
					existingServers.remove(domain);
					String msPath=MajordomoServer.MAJORDOMO_SERVER_DIRECTORY+'/'+domain;
					LinuxServerAccount lsa=ms.getLinuxServerAccount();
					int lsaUID=lsa.getUid().getID();
					LinuxServerGroup lsg=ms.getLinuxServerGroup();
					int lsgGID=lsg.getGid().getID();
					UnixFile msUF=new UnixFile(msPath);
					Stat msUFStat = msUF.getStat();
					UnixFile listsUF=new UnixFile(msUF, "lists", false);
					if(
						!msUFStat.exists()
						|| msUFStat.getUid()==UnixFile.ROOT_UID
						|| msUFStat.getGid()==UnixFile.ROOT_GID
					) {
						// Add a new install
						String sharedPath;
						if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
							sharedPath="../../../../usr/majordomo/"+version;
						} else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
							sharedPath="../../../../opt/majordomo-"+version;
						} else throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);

						if(!msUFStat.exists()) msUF.mkdir();
						msUF.setMode(0750);
						new UnixFile(msUF, "digests", false).mkdir().setMode(0700).chown(lsaUID, lsgGID);
						if(!listsUF.getStat().exists()) listsUF.mkdir();
						listsUF.setMode(0750).chown(lsaUID, mailGID);
						UnixFile LogUF=new UnixFile(msUF, "Log", false);
						LogUF.getSecureOutputStream(lsaUID, lsgGID, 0600, false).close();
						UnixFile majordomocfUF=new UnixFile(msUF, "majordomo.cf", false);
						try (ChainWriter out = new ChainWriter(new BufferedOutputStream(majordomocfUF.getSecureOutputStream(lsaUID, lsgGID, 0600, false)))) {
							out.print("#\n"
									+ "# A sample configuration file for majordomo.  The defaults are set by\n"
									+ "# the AO Industries, Inc. scripts.  It may be edited, but beware that\n"
									+ "# editing the wrong entries might break the list server.\n"
									+ "#\n"
									+ "\n"
									+ "\n"
									+ "# $whereami -- What machine am I running on?\n"
									+ "#\n"
									+ "$whereami = \"").print(domain).print("\";\n"
									+ "\n"
									+ "\n"
									+ "# $whoami -- Who do users send requests to me as?\n"
									+ "#\n"
									+ "$whoami = \"majordomo\\@$whereami\";\n"
									+ "\n"
									+ "\n"
									+ "# $whoami_owner -- Who is the owner of the above, in case of problems?\n"
									+ "#\n"
									+ "$whoami_owner = \"majordomo-owner\\@$whereami\";\n"
									+ "\n"
									+ "\n"
									+ "# $homedir -- Where can I find my extra .pl files, like majordomo.pl?\n"
									+ "# the environment variable HOME is set by the wrapper\n"
									+ "#\n"
									+ "if ( defined $ENV{\"HOME\"}) {\n"
									+ "     $homedir = $ENV{\"HOME\"};\n"
									+ "} else {\n"
									+ "     $homedir = \"").print(msPath).print("\";\n"
									+ "}\n"
									+ "\n"
									+ "\n"
									+ "# $listdir -- Where are the mailing lists?\n"
									+ "#\n"
									+ "$listdir = \"$homedir/lists\";\n"
									+ "\n"
									+ "\n"
									+ "# $digest_work_dir -- the parent directory for digest's queue area\n"
									+ "# Each list must have a subdirectory under this directory in order for\n"
									+ "# digest to work. E.G. The bblisa list would use:\n"
									+ "#       /usr/local/mail/digest/bblisa\n"
									+ "# as its directory.\n"
									+ "#\n"
									+ "$digest_work_dir = \"").print(msPath).print("/digests\";\n"
									+ "\n"
									+ "\n"
									+ "# $log -- Where do I write my log?\n"
									+ "#\n"
									+ "$log = \"$homedir/Log\";\n"
									+ "\n"
									+ "\n"
									+ "# $sendmail_command -- Pathname to the sendmail program\n"
									+ "#                      usually /usr/lib/sendmail, but some newer BSD systems\n"
									+ "#                      seem to prefer /usr/sbin/sendmail\n"
									+ "#\n"
									+ "$sendmail_command = \"/usr/lib/sendmail\";\n"
									+ "\n"
									+ "\n"
									+ "# $mailer -- What program and args do I use to send mail to the list?\n"
									+ "# $bounce_mailer -- What is used to send mail anywhere else?\n"
									+ "# The variables $to, $from, $subject, and $sender can be interpolated into\n"
									+ "# this command line.  Note, however, that the $to, $from, and $subject\n"
									+ "# variables may be provided by the person sending mail, and much mischief\n"
									+ "# can be had by playing with this variable.  It is perfectly safe to use\n"
									+ "# $sender, but the others are insecure.\n"
									+ "#\n"
									+ "# Sendmail option -oi:  Do not take a . on a line by itself as the message\n"
									+ "#                       terminator.\n"
									+ "# Sendmail option -oee: Force sendmail to exit with a zero exit status if\n"
									+ "#                       if it's not going to give useful information.\n"
									+ "#\n"
									+ "$mailer = \"$sendmail_command -oi -oee -f\\$sender\";\n"
									+ "$bounce_mailer = \"$sendmail_command -oi -oee -f\\$sender -t\";\n"
									+ "\n"
									+ "\n"
									+ "# You can special case the mailer used to deliver outbound mail as follows:\n"
									+ "#\n"
									+ "# To use TLB and use no outgoing alias:\n"
									+ "# if ($main'program_name eq 'mj_resend' && $opt_l eq 'test-list') {\n"
									+ "#   $mailer = \"/usr/local/majordomo/tlb /usr/local/lists/${opt_l}.tlb\";\n"
									+ "# }\n"
									+ "#\n"
									+ "# To use a different Sendmail queue for this list's mail:\n"
									+ "# if ($main'program_name eq 'mj_resend' && $opt_l eq 'test-list') {\n"
									+ "#   $mailer = \"$sendmail_command -oQ /var/spool/listq -f\\$sender\";\n"
									+ "# }\n"
									+ "\n"
									+ "\n"
									+ "# You can force Majordomo to delay any processing if the system load is too\n"
									+ "# high by uncommenting the following lines.  THIS ONLY WORKS if your \"uptime\"\n"
									+ "# command (usually found in /usr/bin/uptime or /usr/bsd/uptime)\n"
									+ "# returns a string like:\n"
									+ "#   5:23pm  up  5:51,  9 users,  load average: 0.19, 0.25, 0.33\n"
									+ "#\n"
									+ "$max_loadavg = 40;                 # Choose the maximum allowed load\n"
									+ "$uptime = `/usr/bin/uptime` if -x '/usr/bin/uptime';     # Get system uptime\n"
									+ "($avg_1_minute, $avg_5_minutes, $avg_15_minutes) = \n"
									+ "    $uptime =~ /average:\\s+(\\S+),\\s+(\\S+),\\s+(\\S+)/;\n"
									+ "exit 75 if ($avg_15_minutes >= $max_loadavg);           # E_TEMPFAIL\n"
									+ "\n"
									+ "\n"
									+ "# Set the default subscribe policy for new lists here.\n"
									+ "# If not defined, defaults to \"open\", but in today's increasingly\n"
									+ "# imbecile Internet, \"open+confirm\" or \"auto+confirm\" is a wiser\n"
									+ "# choice for publicly available Majordomo servers.\n"
									+ "#\n"
									+ "$config'default_subscribe_policy = \"open+confirm\";\n"
									+ "\n"
									+ "\n"
									+ "#  Configure X400 parsing here.  This is functional, but not well tested\n"
									+ "#  and rather a hack.\n"
									+ "#  By default all addresses that look x400-ish will be checked for a\n"
									+ "#  @ sign (meaning that it's headed to an smtp->x400 gateway, as well\n"
									+ "#  as the 'c=' and 'a[dm]=' parts, which mean something as well.\n"
									+ "#\n"
									+ "#  If you will be receiving x400 style return addresses that do not have\n"
									+ "#  an @ sign in them indicating an smtp->x400 gateway, set $no_x400at to 1.\n"
									+ "#  Otherwise, leave $no_x400 at 0.\n"
									+ "#\n"
									+ "$no_x400at = 0;\n"
									+ "#\n"
									+ "#  If you will be receiving x400 addresses without the c= or a[dm]= parts\n"
									+ "#  set the $no_true_x400 variable to 1.  This will disable checking for\n"
									+ "#   \"c=\" and \"a[dm]=\" pieces.\n"
									+ "#\n"
									+ "$no_true_x400 = 0;\n"
									+ "\n"
									+ "\n"
									+ "#--------------------------------------------------------------------\n"
									+ "#    Stuff below here isn't commonly changed....\n"
									+ "#--------------------------------------------------------------------\n"
									+ "#\n"
									+ "# Majordomo will look for \"get\" and \"index\" files related to $list in\n"
									+ "# directory \"$filedir/$list$filedir_suffix\", so set $filedir and\n"
									+ "# $filedir_suffix appropriately.  For instance, to look in\n"
									+ "# /usr/local/mail/files/$list, use:\n"
									+ "#   $filedir = \"/usr/local/mail/files\";\n"
									+ "#   $filedir_suffix = \"\";               # empty string\n"
									+ "# or to look in $listdir/$list.archive, use:\n"
									+ "#   $filedir = \"$listdir\";\n"
									+ "#   $filedir_suffix = \".archive\";\n"
									+ "$filedir = \"$listdir\";\n"
									+ "$filedir_suffix = \".archive\";\n"
									+ "\n"
									+ "\n"
									+ "# What command should I use to process an \"index\" request?\n"
									+ "#\n"
									+ "$index_command = \"/bin/ls -lRL\";\n"
									+ "\n"
									+ "\n"
									+ "# If you want to use FTPMAIL, rather than local access, for file transfer\n"
									+ "# and access, define the following:\n"
									+ "#   $ftpmail_address = \"ftpmail\\@decwrl.dec.com\";\n"
									+ "#   $ftpmail_location = \"FTP.$whereami\";\n"
									+ "\n"
									+ "\n"
									+ "# if you want the subject of the request to be included as part of the\n"
									+ "# subject of the reply (useful when automatically testing, or submitting\n"
									+ "# multiple command sets), set $return_subject to 1.\n"
									+ "#\n"
									+ "$return_subject = 1;\n"
									+ "\n"
									+ "\n"
									+ "# If you are using majordomo at the -request address, set the\n"
									+ "# following variable to 1. This affects the welcome message that is\n"
									+ "# sent to a new subscriber as well as the help text that is generated.\n"
									+ "#\n"
									+ "$majordomo_request = 0;\n"
									+ "\n"
									+ "\n"
									+ "# If you have lists that have who turned off, but still allow which\n"
									+ "# requests to work for subscribed members, and you don't want to have\n"
									+ "# \"which @\" to act like a who, the variable $max_which_hits sets the\n"
									+ "# number of hits that are allowed using which before an error is returned.\n"
									+ "# Arguably this should be a per list settable number.\n"
									+ "#\n"
									+ "$max_which_hits = 0;\n"
									+ "\n"
									+ "\n"
									+ "# Set the umask for the process. Used to set default file status for\n"
									+ "# config file.\n"
									+ "#\n"
									+ "umask(007);\n"
									+ "$config_umask = 007;\n"
									+ "\n"
									+ "\n"
									+ "# don't change this. It checks to make sure that you have a new enough\n"
									+ "# version of perl to run majordomo. It is in here because this file is\n"
									+ "# used by almost all of the majordomo programs.\n"
									+ "#\n"
									+ "die \"Perl version $] too old\\n\" if ($] < 4.019);\n"
									+ "\n"
									+ "\n"
									+ "# the safe locations for archive directories\n"
									+ "# None of the parameters that use safedirs are actually used, so\n"
									+ "# @safedirs is a placeholder for future functionality.\n"
									+ "# Just ignore it for version 1.90 through 1.94.\n"
									+ "#\n"
									+ "@safedirs = ( );\n"
									+ "\n"
									+ "\n"
									+ "# Directory where resend temporarily puts its rewritten output message.\n"
									+ "# For the paranoid, this could be changed to a directory that only\n"
									+ "# majordomo has r/w permission to.\n"
									+ "# Uses the environment variable TMPDIR, since that's pretty common\n"
									+ "#\n"
									+ "$TMPDIR = $ENV{'TMPDIR'} || \"/usr/tmp\";\n"
									+ "\n"
									+ "\n"
									+ "# Tune how long set_lock tries to obtain a lock before giving up. Each\n"
									+ "# attempt waits 1 to 10 seconds before trying again and waittime is\n"
									+ "# the total minimum time spent trying. This defaults to 600 seconds (5\n"
									+ "# minutes), which translates to no less then 60 nor more than 600 tries.\n"
									+ "#\n"
									+ "# $shlock'waittime = 1200;\n"
									+ "\n"
									+ "\n"
									+ "# tune the cookie for subscribe_policy=confirm.  Normally this is\n"
									+ "# set to $homedir.  *Don't* make this something like rand(400),\n"
									+ "# the key isn't saved between sessions.\n"
									+ "#\n"
									+ "# $cookie_seed = \"Harry Truman, Doris Day, Red China, Johnnie Ray\" .\n"
									+ "# \" South Pacific, Walter Winchell, Joe DiMaggio\";\n"
									+ "\n"
									+ "\n"
									+ "# The maximum character length of the header lines for resend\n"
									+ "#\n"
									+ "$MAX_HEADER_LINE_LENGTH = 128;\n"
									+ "\n"
									+ "\n"
									+ "# The maximum character length of the _entire_ header for resend\n"
									+ "#\n"
									+ "$MAX_TOTAL_HEADER_LENGTH = 1024;\n"
									+ "\n"
									+ "\n"
									+ "# List of perl regular expressions that, if found in the headers of a message,\n"
									+ "# will cause the message to be bounced to the list approver.\n"
									+ "# Put each regular expression on a separate line before the \"END\" mark, with\n"
									+ "# no trailing \";\"\n"
									+ "# For example:\n"
									+ "#   $global_taboo_headers = <<'END';\n"
									+ "#   /^from:.*trouble\\@hassle\\.net/i\n"
									+ "#   /^subject:.*non-delivery notice/i\n"
									+ "#   END\n"
									+ "# NOTE! Using ' instead of \" in the 'END' is VERY IMPORTANT!!!\n"
									+ "#\n"
									+ "\n"
									+ "\n"
									+ "# Administrative checks.  These used to be buried in the resend code\n"
									+ "#\n"
									+ "$admin_headers = <<'END';\n"
									+ "/^subject:\\s*subscribe\\b/i\n"
									+ "/^subject:\\s*unsubscribe\\b/i\n"
									+ "/^subject:\\s*uns\\w*b/i\n"
									+ "/^subject:\\s*.*un-sub/i\n"
									+ "/^subject:\\s*help\\b/i\n"
									+ "/^subject:\\s.*\\bchange\\b.*\\baddress\\b/i\n"
									+ "/^subject:\\s*request\\b(.*\\b)?addition\\b/i\n"
									+ "/^subject:\\s*cancel\\b/i\n"
									+ "END\n"
									+ "\n"
									+ "\n"
									+ "# Common things that people send to the wrong address.\n"
									+ "# These are caught in the first 10 lines of the message body\n"
									+ "# if 'administrivia' is turned on and the message isn't marked approved.\n"
									+ "#\n"
									+ "# The code that catches this should transparently redirect\n"
									+ "# majordomo commands to majordomo.  That would give the additional\n"
									+ "# advantage of not having to add to this silly construct for\n"
									+ "# each new majordomo command.\n"
									+ "#\n"
									+ "$admin_body = <<'END';\n"
									+ "/\\bcancel\\b/i\n"
									+ "/\\badd me\\b/i\n"
									+ "/\\bdelete me\\b/i\n"
									+ "/\\bremove\\s+me\\b/i\n"
									+ "/\\bchange\\b.*\\baddress\\b/\n"
									+ "/\\bsubscribe\\b/i\n"
									+ "/^sub\\b/i\n"
									+ "/\\bunsubscribe\\b/i\n"
									+ "/^unsub\\b/i\n"
									+ "/\\buns\\w*b/i\n"
									+ "/^\\s*help\\s*$/i\n"
									+ "/^\\s*info\\s*$/i\n"
									+ "/^\\s*info\\s+\\S+\\s*$/i\n"
									+ "/^\\s*lists\\s*$/i\n"
									+ "/^\\s*which\\s*$/i\n"
									+ "/^\\s*which\\s+\\S+\\s*$/i\n"
									+ "/^\\s*index\\s*$/i\n"
									+ "/^\\s*index\\s+\\S+\\s*$/i\n"
									+ "/^\\s*who\\s*$/i\n"
									+ "/^\\s*who\\s+\\S+\\s*$/i\n"
									+ "/^\\s*get\\s+\\S+\\s*$/i\n"
									+ "/^\\s*get\\s+\\S+\\s+\\S+\\s*$/i\n"
									+ "/^\\s*approve\\b/i\n"
									+ "/^\\s*passwd\\b/i\n"
									+ "/^\\s*newinfo\\b/i\n"
									+ "/^\\s*config\\b/i\n"
									+ "/^\\s*newconfig\\b/i\n"
									+ "/^\\s*writeconfig\\b/i\n"
									+ "/^\\s*mkdigest\\b/i\n"
									+ "END\n"
									+ "\n"
									+ "\n"
									+ "# taboo headers to catch\n"
									+ "#\n"
									+ "$global_taboo_headers = <<'END';\n"
									+ "/^subject: ndn: /i\n"
									+ "/^subject:\\s*RCPT:/i\n"
									+ "/^subject:\\s*Delivery Confirmation\\b/i\n"
									+ "/^subject:\\s*NON-DELIVERY of:/i\n"
									+ "/^subject:\\s*Undeliverable Message\\b/i\n"
									+ "/^subject:\\s*Receipt Confirmation\\b/i\n"
									+ "/^subject:\\s*Failed mail\\b/i\n"
									+ "/^subject:\\s*Returned mail\\b/i\n"
									+ "/^subject:\\s*unable to deliver mail\\b/i\n"
									+ "/^subject:\\s.*\\baway from my mail\\b/i\n"
									+ "/^subject:\\s*Autoreply/i\n"
									+ "END\n"
									+ "\n"
									+ "\n"
									+ "# Taboo body contents to catch and forward to the approval address\n"
									+ "#\n"
									+ "# For example:\n"
									+ "#   $global_taboo_body = <<'END';\n"
									+ "#   /taboo topic/i\n"
									+ "#   /another taboo/i\n"
									+ "#   END\n"
									+ "# NOTE! Using ' instead of \" in the next line is VERY IMPORTANT!!!\n"
									+ "#\n"
									+ "$global_taboo_body = <<'END';\n"
									+ "END\n"
									+ "\n"
									+ "\n"
									+ "# Majordomo will not send replies to addresses which match this.\n"
									+ "# The match is done case-insensitively.\n"
									+ "$majordomo_dont_reply = '(mailer-daemon|uucp|listserv|majordomo|listproc)\\@';\n"
									+ "\n"
									+ "\n"
									+ "1;\n"
							);
						}

						// Compile, install, and remove the wrapper
						String source;
						if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
							source = "/usr/aoserv/bin/majordomo-wrapper-"+version+".c";
						} else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
							source = "/opt/majordomo-"+version+"/src/majordomo-wrapper.c";
						} else throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);
						String[] cc={
							"/usr/bin/cc",
							"-DBIN=\""+msPath+"\"",
							"-DPATH=\"PATH=/bin:/usr/bin:/usr/ucb\"",
							"-DHOME=\"HOME="+msPath+"\"",
							"-DSHELL=\"SHELL=/bin/sh\"",
							"-DMAJORDOMO_CF=\"MAJORDOMO_CF="+msPath+"/majordomo.cf\"",
							"-DPOSIX_UID="+lsaUID,
							"-DPOSIX_GID="+lsgGID,
							"-o",
							msPath+"/wrapper",
							source
						};
						AOServDaemon.exec(cc);
						String[] strip={
							"strip",
							msPath+"/wrapper"
						};
						AOServDaemon.exec(strip);
						new UnixFile(msPath, "wrapper").chown(UnixFile.ROOT_UID, mailGID).setMode(04750);

						//  Make the symbolic links
						new UnixFile(msPath, "Tools").symLink(sharedPath+"/Tools").chown(lsaUID, lsgGID);
						new UnixFile(msPath, "archive2.pl").symLink(sharedPath+"/archive2.pl").chown(lsaUID, lsgGID);
						new UnixFile(msPath, "bin").symLink(sharedPath+"/bin").chown(lsaUID, lsgGID);
						new UnixFile(msPath, "bounce-remind").symLink(sharedPath+"/bounce-remind").chown(lsaUID, lsgGID);
						new UnixFile(msPath, "config-test").symLink(sharedPath+"/config-test").chown(lsaUID, lsgGID);
						new UnixFile(msPath, "config_parse.pl").symLink(sharedPath+"/config_parse.pl").chown(lsaUID, lsgGID);
						new UnixFile(msPath, "digest").symLink(sharedPath+"/digest").chown(lsaUID, lsgGID);
						new UnixFile(msPath, "majordomo").symLink(sharedPath+"/majordomo").chown(lsaUID, lsgGID);
						new UnixFile(msPath, "majordomo.pl").symLink(sharedPath+"/majordomo.pl").chown(lsaUID, lsgGID);
						new UnixFile(msPath, "majordomo_version.pl").symLink(sharedPath+"/majordomo_version.pl").chown(lsaUID, lsgGID);
						new UnixFile(msPath, "man").symLink(sharedPath+"/man").chown(lsaUID, lsgGID);
						new UnixFile(msPath, "request-answer").symLink(sharedPath+"/request-answer").chown(lsaUID, lsgGID);
						new UnixFile(msPath, "resend").symLink(sharedPath+"/resend").chown(lsaUID, lsgGID);
						new UnixFile(msPath, "sample.cf").symLink(sharedPath+"/sample.cf").chown(lsaUID, lsgGID);
						new UnixFile(msPath, "shlock.pl").symLink(sharedPath+"/shlock.pl").chown(lsaUID, lsgGID);

						// Flag as successful by ownership
						msUF.chown(lsaUID, mailGID);
					}

					// Verify the correct lists are installed
					String[] listFiles=listsUF.list();
					Set<String> existingListFiles=new HashSet<>(listFiles.length*4/3+1);
					existingListFiles.addAll(Arrays.asList(listFiles));

					// Add any new files, allow some files to stay
					List<MajordomoList> mls=ms.getMajordomoLists();
					for(MajordomoList ml : mls) {
						String listName=ml.getName();

						// Make the list file
						UnixFile listUF=new UnixFile(listsUF, listName, false);
						if(!listUF.getStat().exists()) {
							listUF.getSecureOutputStream(lsaUID, lsgGID, 0644, false).close();
						} else listUF.setMode(0644).chown(lsaUID, lsgGID);
						existingListFiles.remove(listName);

						// Make the .config file
						UnixFile configUF=new UnixFile(listsUF, listName+".config", false);
						if(!configUF.getStat().exists()) {
							try (ChainWriter out = new ChainWriter(configUF.getSecureOutputStream(lsaUID, lsgGID, 0660, false))) {
								out.print("# The configuration file for a majordomo mailing list.\n"
										+ "# Comments start with the first # on a line, and continue to the end\n"
										+ "# of the line. There is no way to escape the # character. The file\n"
										+ "# uses either a key = value for simple (i.e. a single) values, or uses\n"
										+ "# a here document\n"
										+ "#     key << END \n"
										+ "#     value 1\n"
										+ "#     value 2\n"
										+ "#     [ more values 1 per line]\n"
										+ "#     END \n"
										+ "# for installing multiple values in array types. Note that the here\n"
										+ "# document delimiter (END in the example above) must be the same at the end\n"
										+ "# of the list of entries as it is after the << characters.\n"
										+ "# Within a here document, the # sign is NOT a comment character.\n"
										+ "# A blank line is allowed only as the last line in the here document.\n"
										+ "#\n"
										+ "# The values can have multiple forms:\n"
										+ "#\n"
										+ "#\tabsolute_dir -- A root anchored (i.e begins with a /) directory \n"
										+ "#\tabsolute_file -- A root anchored (i.e begins with a /) file \n"
										+ "#\tbool -- choose from: yes, no, y, n\n"
										+ "#\tenum -- One of a list of possible values\n"
										+ "#\tinteger -- an integer (string made up of the digits 0-9,\n"
										+ "#\t	   no decimal point)\n"
										+ "#\tfloat -- a floating point number with decimal point.\n"
										+ "#\tregexp -- A perl style regular expression with\n"
										+ "#\t\t\t  leading and trailing /'s.\n"
										+ "#\trestrict_post -- a series of space or : separated file names in which\n"
										+ "#                        to look up the senders address\n"
										+ "#\t           (restrict-post should go away to be replaced by an\n"
										+ "#\t\t     array of files)\n"
										+ "#\tstring -- any text up until a \\n stripped of\n"
										+ "#\t\t  leading and trailing whitespace\n"
										+ "#\tword -- any text with no embedded whitespace\n"
										+ "#\n"
										+ "# A blank value is also accepted, and will undefine the corresponding keyword.\n"
										+ "# The character Control-A may not be used in the file.\n"
										+ "#\n"
										+ "# A trailing _array on any of the above types means that that keyword\n"
										+ "# will allow more than one value.\n"
										+ "#\n"
										+ "# Within a here document for a string_array, the '-' sign takes on a special\n"
										+ "# significance.\n"
										+ "#\n"
										+ "#     To embed a blank line in the here document, put a '-' as the first\n"
										+ "#       and ONLY character on the line.\n"
										+ "#\n"
										+ "#     To preserve whitespace at the beginning of a line, put a - on the\n"
										+ "#       line before the whitespace to be preserved\n"
										+ "#\n"
										+ "#     To put a literal '-' at the beginning of a line, double it.\n"
										+ "#\n"
										+ "#\n"
										+ "# The default if the keyword is not supplied is given in ()'s while the \n"
										+ "# type of value is given in [], the subsystem the keyword is used in is\n"
										+ "# listed in <>'s. (undef) as default value means that the keyword is not\n"
										+ "# defined or used.\n"
										+ "\n"
										+ "\t# admin_passwd         [word] (").print(listName).print(".admin) <majordomo>\n"
										+ "\t# The password for handling administrative tasks on the list.\n"
										+ "admin_passwd        =   ").print(listName).print(".admin\n"
										+ "\n"
										+ "\t# administrivia        [bool] (yes) <resend>\n"
										+ "\t# Look for administrative requests (e.g. subscribe/unsubscribe) and\n"
										+ "\t# forward them to the list maintainer instead of the list.\n"
										+ "administrivia       =   yes\n"
										+ "\n"
										+ "\t# advertise            [regexp_array] (undef) <majordomo>\n"
										+ "\t# If the requestor email address matches one of these regexps, then\n"
										+ "\t# the list will be listed in the output of a lists command. Failure\n"
										+ "\t# to match any regexp excludes the list from the output. The\n"
										+ "\t# regexps under noadvertise override these regexps.\n"
										+ "advertise           <<  END\n"
										+ "\n"
										+ "END\n"
										+ "\n"
										+ "\t# announcements        [bool] (yes) <majordomo>\n"
										+ "\t# If set to yes, comings and goings to the list will be sent to the\n"
										+ "\t# list owner. These SUBSCRIBE/UNSUBSCRIBE event announcements are\n"
										+ "\t# informational only (no action is required), although it is highly\n"
										+ "\t# recommended that they be monitored to watch for list abuse.\n"
										+ "announcements       =   yes\n"
										+ "\n"
										+ "\t# approve_passwd       [word] (").print(listName).print(".pass) <resend>\n"
										+ "\t# Password to be used in the approved header to allow posting to\n"
										+ "\t# moderated list, or to bypass resend checks.\n"
										+ "approve_passwd      =   ").print(listName).print(".pass\n"
										+ "\n"
										+ "\t# archive_dir          [absolute_dir] (undef) <majordomo>\n"
										+ "\t# The directory where the mailing list archive is kept. This item\n"
										+ "\t# does not currently work. Leave it blank.\n"
										+ "archive_dir         =\n"
										+ "\n"
										+ "\t# comments             [string_array] (undef) <config>\n"
										+ "\t# Comment string that will be retained across config file rewrites.\n"
										+ "comments            <<  END\n"
										+ "\n"
										+ "END\n"
										+ "\n"
										+ "\t# date_info            [bool] (yes) <majordomo>\n"
										+ "\t# Put the last updated date for the info file at the top of the\n"
										+ "\t# info file rather than having it appended with an info command.\n"
										+ "\t# This is useful if the file is being looked at by some means other\n"
										+ "\t# than majordomo (e.g. finger).\n"
										+ "date_info           =   yes\n"
										+ "\n"
										+ "\t# date_intro           [bool] (yes) <majordomo>\n"
										+ "\t# Put the last updated date for the intro file at the top of the\n"
										+ "\t# intro file rather than having it appended with an intro command.\n"
										+ "\t# This is useful if the file is being looked at by some means other\n"
										+ "\t# than majordomo (e.g. finger).\n"
										+ "date_intro          =   yes\n"
										+ "\n"
										+ "\t# debug                [bool] (no) <resend>\n"
										+ "\t# Don't actually forward message, just go though the motions.\n"
										+ "debug               =   no\n"
										+ "\n"
										+ "\t# description          [string] (undef) <majordomo>\n"
										+ "\t# Used as description for mailing list when replying to the lists\n"
										+ "\t# command. There is no quoting mechanism, and there is only room\n"
										+ "\t# for 50 or so characters.\n"
										+ "description         =\n"
										+ "\n"
										+ "\t# digest_archive       [absolute_dir] (undef) <digest>\n"
										+ "\t# The directory where the digest archive is kept. This item does\n"
										+ "\t# not currently work. Leave it blank.\n"
										+ "digest_archive      =\n"
										+ "\n"
										+ "\t# digest_issue         [integer] (1) <digest>\n"
										+ "\t# The issue number of the next issue\n"
										+ "digest_issue        =   1\n"
										+ "\n"
										+ "\t# digest_maxdays       [integer] (undef) <digest>\n"
										+ "\t# automatically generate a new digest when the age of the oldest\n"
										+ "\t# article in the queue exceeds this number of days.\n"
										+ "digest_maxdays      =\n"
										+ "\n"
										+ "\t# digest_maxlines      [integer] (undef) <digest>\n"
										+ "\t# automatically generate a new digest when the size of the digest\n"
										+ "\t# exceeds this number of lines.\n"
										+ "digest_maxlines     =\n"
										+ "\n"
										+ "\t# digest_name          [string] (").print(listName).print(") <digest>\n"
										+ "\t# The subject line for the digest. This string has the volume  and\n"
										+ "\t# issue appended to it.\n"
										+ "digest_name         =   ").print(listName).print("\n"
										+ "\n"
										+ "\t# digest_rm_footer     [word] (undef) <digest>\n"
										+ "\t# The value is the name of the list that applies the header and\n"
										+ "\t# footers to the messages that are received by digest. This allows\n"
										+ "\t# the list supplied headers and footers to be stripped before the\n"
										+ "\t# messages are included in the digest.\n"
										+ "digest_rm_footer    =\n"
										+ "\n"
										+ "\t# digest_rm_fronter    [word] (undef) <digest>\n"
										+ "\t# Works just like digest_rm_footer, except it removes the front\n"
										+ "\t# material.\n"
										+ "digest_rm_fronter   =\n"
										+ "\n"
										+ "\t# digest_volume        [integer] (1) <digest>\n"
										+ "\t# The current volume number\n"
										+ "digest_volume       =   1\n"
										+ "\n"
										+ "\t# digest_work_dir      [absolute_dir] (undef) <digest>\n"
										+ "\t# The directory used as scratch space for digest. Don't  change\n"
										+ "\t# this unless you know what you are doing\n"
										+ "digest_work_dir     =\n"
										+ "\n"
										+ "\t# get_access           [enum] (list) <majordomo> /open;closed;list/\n"
										+ "\t# One of three values: open, list, closed. Open allows anyone\n"
										+ "\t# access to this command and closed completely disables the command\n"
										+ "\t# for everyone. List allows only list members access, or if\n"
										+ "\t# restrict_post is defined, only the addresses in those files are\n"
										+ "\t# allowed access.\n"
										+ "get_access          =   list\n"
										+ "\n"
										+ "\t# index_access         [enum] (open) <majordomo> /open;closed;list/\n"
										+ "\t# One of three values: open, list, closed. Open allows anyone\n"
										+ "\t# access to this command and closed completely disables the command\n"
										+ "\t# for everyone. List allows only list members access, or if\n"
										+ "\t# restrict_post is defined, only the addresses in those files are\n"
										+ "\t# allowed access.\n"
										+ "index_access        =   open\n"
										+ "\n"
										+ "\t# info_access          [enum] (open) <majordomo> /open;closed;list/\n"
										+ "\t# One of three values: open, list, closed. Open allows anyone\n"
										+ "\t# access to this command and closed completely disables the command\n"
										+ "\t# for everyone. List allows only list members access, or if\n"
										+ "\t# restrict_post is defined, only the addresses in those files are\n"
										+ "\t# allowed access.\n"
										+ "info_access         =   open\n"
										+ "\n"
										+ "\t# intro_access         [enum] (list) <majordomo> /open;closed;list/\n"
										+ "\t# One of three values: open, list, closed. Open allows anyone\n"
										+ "\t# access to this command and closed completely disables the command\n"
										+ "\t# for everyone. List allows only list members access, or if\n"
										+ "\t# restrict_post is defined, only the addresses in those files are\n"
										+ "\t# allowed access.\n"
										+ "intro_access        =   list\n"
										+ "\n"
										+ "\t# maxlength            [integer] (40000) <resend,digest>\n"
										+ "\t# The maximum size of an unapproved message in characters. When\n"
										+ "\t# used with digest, a new digest will be automatically generated if\n"
										+ "\t# the size of the digest exceeds this number of characters.\n"
										+ "maxlength           =   40000\n"
										+ "\n"
										+ "\t# message_footer       [string_array] (undef) <resend,digest>\n"
										+ "\t# Text to be appended at the end of all messages posted to the\n"
										+ "\t# list. The text is expanded before being used. The following\n"
										+ "\t# expansion tokens are defined: $LIST - the name of the current\n"
										+ "\t# list, $SENDER - the sender as taken from the from line, $VERSION,\n"
										+ "\t# the version of majordomo. If used in a digest, no expansion\n"
										+ "\t# tokens are provided\n"
										+ "message_footer      <<  END\n"
										+ "\n"
										+ "END\n"
										+ "\n"
										+ "\t# message_fronter      [string_array] (undef) <resend,digest>\n"
										+ "\t# Text to be prepended to the beginning of all messages posted to\n"
										+ "\t# the list. The text is expanded before being used. The following\n"
										+ "\t# expansion tokens are defined: $LIST - the name of the current\n"
										+ "\t# list, $SENDER - the sender as taken from the from line, $VERSION,\n"
										+ "\t# the version of majordomo. If used in a digest, only the expansion\n"
										+ "\t# token _SUBJECTS_ is available, and it expands to the list of\n"
										+ "\t# message subjects in the digest\n"
										+ "message_fronter     <<  END\n"
										+ "\n"
										+ "END\n"
										+ "\n"
										+ "\t# message_headers      [string_array] (undef) <resend,digest>\n"
										+ "\t# These headers will be appended to the headers of the posted\n"
										+ "\t# message. The text is expanded before being used. The following\n"
										+ "\t# expansion tokens are defined: $LIST - the name of the current\n"
										+ "\t# list, $SENDER - the sender as taken from the from line, $VERSION,\n"
										+ "\t# the version of majordomo.\n"
										+ "message_headers     <<  END\n"
										+ "\n"
										+ "END\n"
										+ "\n"
										+ "\t# moderate             [bool] (no) <resend>\n"
										+ "\t# If yes, all postings to the list will be bounced to the moderator\n"
										+ "\t# for approval.\n"
										+ "moderate            =   no\n"
										+ "\n"
										+ "\t# moderator            [word] (undef) <resend>\n"
										+ "\t# Address for directing posts which require approval. Such\n"
										+ "\t# approvals might include moderated mail, administrivia traps, and\n"
										+ "\t# restrict_post authorizations. If the moderator address is not\n"
										+ "\t# set, it will default to the list-approval address.\n"
										+ "moderator           =\n"
										+ "\n"
										+ "\t# mungedomain          [bool] (no) <majordomo>\n"
										+ "\t# If set to yes, a different method is used to determine a matching\n"
										+ "\t# address.  When set to yes, addresses of the form user@dom.ain.com\n"
										+ "\t# are considered equivalent to addresses of the form user@ain.com.\n"
										+ "\t# This allows a user to subscribe to a list using the domain\n"
										+ "\t# address rather than the address assigned to a particular machine\n"
										+ "\t# in the domain. This keyword affects the interpretation of\n"
										+ "\t# addresses for subscribe, unsubscribe, and all private options.\n"
										+ "mungedomain         =   no\n"
										+ "\n"
										+ "\t# noadvertise          [regexp_array] (undef) <majordomo>\n"
										+ "\t# If the requestor name matches one of these regexps, then the list\n"
										+ "\t# will not be listed in the output of a lists command. Noadvertise\n"
										+ "\t# overrides advertise.\n"
										+ "noadvertise         <<  END\n"
										+ "\n"
										+ "END\n"
										+ "\n"
										+ "\t# precedence           [word] (bulk) <resend,digest>\n"
										+ "\t# Put a precedence header with value <value> into the outgoing\n"
										+ "\t# message.\n"
										+ "precedence          =   bulk\n"
										+ "\n"
										+ "\t# purge_received       [bool] (no) <resend>\n"
										+ "\t# Remove all received lines before resending the message.\n"
										+ "purge_received      =   no\n"
										+ "\n"
										+ "\t# reply_to             [word] () <resend,digest>\n"
										+ "\t# Put a reply-to header with value <value> into the outgoing\n"
										+ "\t# message. If the token $SENDER is used, then the address of the\n"
										+ "\t# sender is used as the value of the reply-to header. This is the\n"
										+ "\t# value of the reply-to header for digest lists.\n"
										+ "reply_to            =   ").print(listName).print('@').print(domain).print("\n"
										+ "\n"
										+ "\t# resend_host          [word] (undef) <resend>\n"
										+ "\t# The host name that is appended to all address strings specified\n"
										+ "\t# for resend.\n"
										+ "resend_host         =\n"
										+ "\n"
										+ "\t# restrict_post        [restrict_post] (undef) <resend>\n"
										+ "\t# If defined, only addresses listed in these files (colon or space\n"
										+ "\t# separated) can post to the mailing list. By default, these files\n"
										+ "\t# are relative to the lists directory. These files are also checked\n"
										+ "\t# when get_access, index_access, info_access, intro_access,\n"
										+ "\t# which_access, or who_access is set to 'list'. This is less useful\n"
										+ "\t# than it seems it should be since there is no way to create these\n"
										+ "\t# files if you do not have access to the machine running resend.\n"
										+ "\t# This mechanism will be replaced in a future version of\n"
										+ "\t# majordomo/resend.\n"
										+ "restrict_post       =\n"
										+ "\n"
										+ "\t# sender               [word] (owner-").print(listName).print(") <majordomo,resend,digest>\n"
										+ "\t# The envelope and sender address for the resent mail. This string\n"
										+ "\t# has \"@\" and the value of resend_host appended to it to make a\n"
										+ "\t# complete address. For majordomo, it provides the sender address\n"
										+ "\t# for the welcome mail message generated as part of the subscribe\n"
										+ "\t# command.\n"
										+ "sender              =   owner-").print(listName).print("\n"
										+ "\n"
										+ "\t# strip                [bool] (yes) <majordomo>\n"
										+ "\t# When adding address to the list, strip off all comments etc, and\n"
										+ "\t# put just the raw address in the list file.  In addition to the\n"
										+ "\t# keyword, if the file <listname>.strip exists, it is the same as\n"
										+ "\t# specifying a yes value. That yes value is overridden by the value\n"
										+ "\t# of this keyword.\n"
										+ "strip               =   yes\n"
										+ "\n"
										+ "\t# subject_prefix       [word] (undef) <resend>\n"
										+ "\t# This word will be prefixed to the subject line, if it is not\n"
										+ "\t# already in the subject. The text is expanded before being used.\n"
										+ "\t# The following expansion tokens are defined: $LIST - the name of\n"
										+ "\t# the current list, $SENDER - the sender as taken from the from\n"
										+ "\t# line, $VERSION, the version of majordomo.\n"
										+ "subject_prefix      =\n"
										+ "\n"
										+ "\t# subscribe_policy     [enum] (open+confirm) <majordomo> /open;closed\n"
										+ "\t# One of three values: open, closed, auto; plus an optional\n"
										+ "\t# modifier: '+confirm'.  Open allows people to subscribe themselves\n"
										+ "\t# to the list. Auto allows anybody to subscribe anybody to the list\n"
										+ "\t# without maintainer approval. Closed requires maintainer approval\n"
										+ "\t# for all subscribe requests to the list.  Adding '+confirm', ie,\n"
										+ "\t# 'open+confirm', will cause majordomo to send a reply back to the\n"
										+ "\t# subscriber which includes a authentication number which must be\n"
										+ "\t# sent back in with another subscribe command.\n"
										+ "subscribe_policy    =   open+confirm\n"
										+ "\n"
										+ "\t# taboo_body           [regexp_array] (undef) <resend>\n"
										+ "\t# If any line of the body matches one of these regexps, then the\n"
										+ "\t# message will be bounced for review.\n"
										+ "taboo_body          <<  END\n"
										+ "\n"
										+ "END\n"
										+ "\n"
										+ "\t# taboo_headers        [regexp_array] (undef) <resend>\n"
										+ "\t# If any of the headers matches one of these regexps, then the\n"
										+ "\t# message will be bounced for review.\n"
										+ "taboo_headers       <<  END\n"
										+ "\n"
										+ "END\n"
										+ "\n"
										+ "\t# unsubscribe_policy   [enum] (open) <majordomo> /open;closed;auto;op\n"
										+ "\t# One of three values: open, closed, auto; plus an optional\n"
										+ "\t# modifier: '+confirm'.  Open allows people to unsubscribe\n"
										+ "\t# themselves from the list. Auto allows anybody to unsubscribe\n"
										+ "\t# anybody to the list without maintainer approval. The existence of\n"
										+ "\t# the file <listname>.auto is the same as specifying the value\n"
										+ "\t# auto.  Closed requires maintainer approval for all unsubscribe\n"
										+ "\t# requests to the list. In addition to the keyword, if the file\n"
										+ "\t# <listname>.closed exists, it is the same as specifying the value\n"
										+ "\t# closed. Adding '+confirm', ie, 'auto+confirm', will cause\n"
										+ "\t# majordomo to send a reply back to the subscriber if the request\n"
										+ "\t# didn't come from the subscriber. The reply includes a\n"
										+ "\t# authentication number which must be sent back in with another\n"
										+ "\t# subscribe command.  The value of this keyword overrides the value\n"
										+ "\t# supplied by any existent files.\n"
										+ "unsubscribe_policy  =   open\n"
										+ "\n"
										+ "\t# welcome              [bool] (yes) <majordomo>\n"
										+ "\t# If set to yes, a welcome message (and optional 'intro' file) will\n"
										+ "\t# be sent to the newly subscribed user.\n"
										+ "welcome             =   yes\n"
										+ "\n"
										+ "\t# which_access         [enum] (open) <majordomo> /open;closed;list/\n"
										+ "\t# One of three values: open, list, closed. Open allows anyone\n"
										+ "\t# access to this command and closed completely disables the command\n"
										+ "\t# for everyone. List allows only list members access, or if\n"
										+ "\t# restrict_post is defined, only the addresses in those files are\n"
										+ "\t# allowed access.\n"
										+ "which_access        =   open\n"
										+ "\n"
										+ "\t# who_access           [enum] (open) <majordomo> /open;closed;list/\n"
										+ "\t# One of three values: open, list, closed. Open allows anyone\n"
										+ "\t# access to this command and closed completely disables the command\n"
										+ "\t# for everyone. List allows only list members access, or if\n"
										+ "\t# restrict_post is defined, only the addresses in those files are\n"
										+ "\t# allowed access.\n"
										+ "who_access          =   open\n");
							}
						}
						existingListFiles.remove(listName+".config");

						// Make the .info file
						UnixFile infoUF=new UnixFile(listsUF, listName+".info", false);
						if(!infoUF.getStat().exists()) infoUF.getSecureOutputStream(lsaUID, lsgGID, 0664, false).close();
						existingListFiles.remove(listName+".info");

						// Allow the .intro file
						UnixFile introUF=new UnixFile(listsUF, listName+".intro", false);
						if(!introUF.getStat().exists()) introUF.getSecureOutputStream(lsaUID, lsgGID, 0664, false).close();
						existingListFiles.remove(listName+".intro");

						// Allow the -post file
						existingListFiles.remove(listName+"-post");
						existingListFiles.remove(listName+".posters");
					}

					// Delete the extra files
					for(String filename : existingListFiles) {
						deleteFileList.add(new File(listsUF.getPath(), filename));
					}
				}

				// Delete the extra directories
				for(DomainName filename : existingServers) {
					deleteFileList.add(new File(MajordomoServer.MAJORDOMO_SERVER_DIRECTORY, filename.toString()));
				}

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
						new UnixFile(file.getPath()).secureDeleteRecursive();
					}
				}
				if(mss.isEmpty()) {
					// Delete the directory if no longer needed (it should be empty already)
					// RPM will clean it up: if(serversUF.getStat().exists()) serversUF.delete();

					// Remove the package
					PackageManager.removePackage(PackageManager.PackageName.MAJORDOMO);
				}
			}
			return true;
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			LogFactory.getLogger(MajordomoManager.class).log(Level.SEVERE, null, T);
			return false;
		}
	}

	public static void start() throws IOException, SQLException {
		AOServer thisAOServer=AOServDaemon.getThisAOServer();
		int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();

		synchronized(System.out) {
			if(
				// Nothing is done for these operating systems
				osv != OperatingSystemVersion.CENTOS_5_DOM0_I686
				&& osv != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				&& osv != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
				// Check config after OS check so config entry not needed
				&& AOServDaemonConfiguration.isManagerEnabled(MajordomoManager.class)
				&& majordomoManager==null
			) {
				System.out.print("Starting MajordomoManager: ");
				AOServConnector connector=AOServDaemon.getConnector();
				majordomoManager=new MajordomoManager();
				connector.getMajordomoLists().addTableListener(majordomoManager, 0);
				connector.getMajordomoServers().addTableListener(majordomoManager, 0);
				System.out.println("Done");
			}
		}
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild Majordomo";
	}
}
