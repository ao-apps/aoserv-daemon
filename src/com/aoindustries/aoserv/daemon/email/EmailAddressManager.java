/*
 * Copyright 2000-2013, 2015, 2016, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.email;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.account.Username;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.email.BlackholeEmailAddress;
import com.aoindustries.aoserv.client.email.EmailAddress;
import com.aoindustries.aoserv.client.email.EmailDomain;
import com.aoindustries.aoserv.client.email.EmailForwarding;
import com.aoindustries.aoserv.client.email.EmailListAddress;
import com.aoindustries.aoserv.client.email.EmailPipeAddress;
import com.aoindustries.aoserv.client.email.LinuxAccAddress;
import com.aoindustries.aoserv.client.email.SystemEmailAlias;
import com.aoindustries.aoserv.client.linux.AOServer;
import com.aoindustries.aoserv.client.linux.LinuxServerAccount;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.client.validator.UserId;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.IoUtils;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.net.DomainName;
import com.aoindustries.util.StringUtility;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;

/**
 * @author  AO Industries, Inc.
 */
final public class EmailAddressManager extends BuilderThread {

	/**
	 * Sendmail files.
	 */
	private static final UnixFile
		aliases = new UnixFile("/etc/aliases"),
		userTable = new UnixFile("/etc/mail/virtusertable")
	;

	/**
	 * Basic system aliases -- these MUST be present.
	 */
	private static final String
		MAILER_DAEMON = "mailer-daemon",
		POSTMASTER = "postmaster";

	private static EmailAddressManager emailAddressManager;

	private EmailAddressManager() {
	}

	private static void writeRequiredSystemAlias(String requiredAddress, List<SystemEmailAlias> systemAliases, Set<String> usernamesUsed, ChainWriter aliasesOut) throws SQLException {
		boolean foundAddress = false;
		Iterator<SystemEmailAlias> iter = systemAliases.iterator();
		while(iter.hasNext()) {
			SystemEmailAlias alias = iter.next();
			String address = alias.getAddress();
			if(requiredAddress.equalsIgnoreCase(address)) {
				if(foundAddress) throw new SQLException("Duplicate system alias \"" + requiredAddress + "\"");
				foundAddress = true;
				iter.remove();
				usernamesUsed.add(address);
				aliasesOut.write(address).write(": ").write(alias.getDestination()).write('\n');
			}
		}
		if(!foundAddress) throw new SQLException("Required system alias \"" + requiredAddress + "\" not found");
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			AOServer thisAoServer = AOServDaemon.getThisAOServer();
			OperatingSystemVersion osv = thisAoServer.getServer().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			synchronized(rebuildLock) {
				Set<UnixFile> restorecon = new LinkedHashSet<>();
				try {
					List<SystemEmailAlias> seas = thisAoServer.getSystemEmailAliases();
					List<EmailAddress> eas = thisAoServer.getEmailAddresses();
					List<EmailDomain> eds = thisAoServer.getEmailDomains();
					assert eas.isEmpty() || !eds.isEmpty() : "Email addresses exist without any domains";

					// Install sendmail if needed
					// Sendmail is enabled/disabled in SendmailCFManager based on net_binds
					boolean sendmailInstalled;
					if(!eds.isEmpty()) {
						PackageManager.installPackage(PackageManager.PackageName.SENDMAIL);
						sendmailInstalled = true;
					} else {
						sendmailInstalled = PackageManager.getInstalledPackage(PackageManager.PackageName.SENDMAIL) != null;
					}

					// Each username may only be used once within the aliases file
					Set<String> usernamesUsed = new HashSet<>();

					// Build the new /etc/aliases file contents
					ByteArrayOutputStream aliasesBOut = new ByteArrayOutputStream();
					// Build the new /etc/mail/virtusertable contents
					ByteArrayOutputStream usersBOut = new ByteArrayOutputStream();
					try (
						ChainWriter aliasesOut = new ChainWriter(aliasesBOut);
						ChainWriter usersOut = new ChainWriter(usersBOut)
					) {
						aliasesOut.print(
							"#\n"
							+ "#  Aliases in this file will NOT be expanded in the header from\n"
							+ "#  Mail, but WILL be visible over networks or from /bin/mail.\n"
							+ "#\n"
							+ "#\t>>>>>>>>>>\tThe program \"newaliases\" must be run after\n"
							+ "#\t>> NOTE >>\tthis file is updated for any changes to\n"
							+ "#\t>>>>>>>>>>\tshow through to sendmail.\n"
							+ "#\n"
							+ "# Generated by ").print(EmailAddressManager.class.getName()).print("\n");
						usersOut.print("# Generated by ").print(EmailAddressManager.class.getName()).print("\n");
						{
							List<SystemEmailAlias> systemAliases = new ArrayList<>(seas);
							// Basic system aliases
							aliasesOut.print(
								"\n"
								+ "# Basic system aliases -- these MUST be present.\n"
							);
							writeRequiredSystemAlias(MAILER_DAEMON, systemAliases, usernamesUsed, aliasesOut);
							writeRequiredSystemAlias(POSTMASTER, systemAliases, usernamesUsed, aliasesOut);
							if(!systemAliases.isEmpty()) {
								// Additional system aliases
								aliasesOut.print(
									"\n"
									+ "# Additional system aliases.\n"
								);
								for(SystemEmailAlias alias : systemAliases) {
									String address = alias.getAddress();
									usernamesUsed.add(address);
									aliasesOut.print(address).print(": ").println(alias.getDestination());
								}
							}
						}
						// Block default username-based aliases, all are listed in /etc/mail/virtusertable
						{
							String ex_nouser;
							if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
								//ex_nouser="/opt/aoserv-client/sbin/ex_nouser";
								// Code for EX_NOUSER in /usr/include/sysexits.h
								ex_nouser="\"/bin/sh -c 'exit 67'\"";
							} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
								// Code for EX_NOUSER in /usr/include/sysexits.h
								ex_nouser="\"exit 67\"";
							} else {
								throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
							}
							boolean didOne = false;
							for(LinuxServerAccount lsa : thisAoServer.getLinuxServerAccounts()) {
								String username = lsa.getLinuxAccount().getUsername().getUsername().toString();
								if(!usernamesUsed.contains(username)) {
									if(username.indexOf('@') == -1) {
										if(!didOne) {
											aliasesOut.print(
												"\n"
												+ "# Block default username-based aliases to prevent unexpected cross-domain delivery.\n"
												+ "# Each specific address@domain combination is listed in /etc/mail/virtusertable.\n"
											);
											didOne = true;
										}
										aliasesOut.print(username).print(": |").println(ex_nouser);
									}
									usernamesUsed.add(username);
								}
							}
						}
						// Specific address@domain combinations
						String[] devNullUsername = new String[1];
						Map<String,String> singleForwardingTies = new HashMap<>();
						Map<UnixPath,String> singleListTies = new HashMap<>();
						Map<String,String> singlePipeTies = new HashMap<>();
						Map<UserId,String> singleInboxTies = new HashMap<>();
						{
							boolean didOne = false;
							for(EmailAddress ea : eas) {
								String address = ea.getAddress();
								if(address.length() > 0) {
									if(!didOne) {
										aliasesOut.print(
											"\n"
											+ "# Specific address@domain combinations.\n"
										);
										usersOut.print(
											"\n"
											+ "# Specific address@domain combinations.\n"
										);
										didOne = true;
									}
									writeEmailAddressConfigs(
										ea,
										usernamesUsed,
										devNullUsername,
										singleForwardingTies,
										singleListTies,
										singlePipeTies,
										singleInboxTies,
										aliasesOut,
										usersOut
									);
								}
							}
						}
						{
							boolean didOne = false;
							for(EmailDomain ed : eds) {
								DomainName domain = ed.getDomain();
								for(SystemEmailAlias sea : seas) {
									String address = sea.getAddress();
									if(ed.getEmailAddress(address) == null) {
										if(!didOne) {
											usersOut.print(
												"\n"
												+ "# Pass-through system aliases not otherwise specified.\n"
											);
											didOne = true;
										}
										usersOut.print(address).print('@').print(domain).print('\t').print(address).print('\n');
									}
								}
							}
						}
						{
							boolean didOne = false;
							for(EmailAddress ea : eas) {
								String address = ea.getAddress();
								if(address.length() == 0) {
									if(!didOne) {
										aliasesOut.print(
											"\n"
											+ "# Catch-all @domain addresses.\n"
										);
										usersOut.print(
											"\n"
											+ "# Catch-all @domain addresses.\n"
										);
										didOne = true;
									}
									writeEmailAddressConfigs(
										ea,
										usernamesUsed,
										devNullUsername,
										singleForwardingTies,
										singleListTies,
										singlePipeTies,
										singleInboxTies,
										aliasesOut,
										usersOut
									);
								}
							}
						}
					}

					// Only write to disk if changed, this will almost always be the case when
					// tie usernames are used for any reason, but this will help for servers with
					// simple configurations.
					boolean needMakeMap = sendmailInstalled && DaemonFileUtils.atomicWrite(
						userTable,
						usersBOut.toByteArray(),
						0644,
						UnixFile.ROOT_UID,
						UnixFile.ROOT_GID,
						null,
						restorecon
					);
					boolean needNewAliases = DaemonFileUtils.atomicWrite(
						aliases,
						aliasesBOut.toByteArray(),
						0644,
						UnixFile.ROOT_UID,
						UnixFile.ROOT_GID,
						null,
						restorecon
					);

					// SELinux before next steps
					DaemonFileUtils.restorecon(restorecon);
					restorecon.clear();

					if(sendmailInstalled) {
						// Rebuild the hash map
						if(needMakeMap) makeMap();

						// Call newaliases
						if(needNewAliases) newAliases();
					}
				} finally {
					DaemonFileUtils.restorecon(restorecon);
				}
			}
			return true;
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			LogFactory.getLogger(EmailAddressManager.class).log(Level.SEVERE, null, T);
			return false;
		}
	}

	private static void writeEmailAddressConfigs(
		EmailAddress ea,
		Set<String> usernamesUsed,
		String[] devNullUsername,
		Map<String,String> singleForwardingTies,
		Map<UnixPath,String> singleListTies,
		Map<String,String> singlePipeTies,
		Map<UserId,String> singleInboxTies,
		ChainWriter aliasesOut,
		ChainWriter usersOut
	) throws IOException, SQLException {
		String address = ea.getAddress();
		DomainName domain = ea.getDomain().getDomain();

		/*
		 * The possible email deliveries:
		 *
		 * 1) /dev/null only
		 * 2) One forwarding destination, BEA ignored (use singleForwardingTies)
		 * 3) One email list, BEA ignored (use singleListTies)
		 * 4) One pipe, BEA ignored (use singlePipeTies)
		 * 5) One Inbox only, BEA ignored (use singleInboxTies)
		 * 6) Multiple destinations, BEA ignored (list each)
		 * 7) Nothing (ignore)
		 */
		BlackholeEmailAddress bea = ea.getBlackholeEmailAddress();
		List<EmailForwarding> efs = ea.getEmailForwardings();
		// We need to not forward email for disabled accounts, but do we just reject it instead?
		// List<EmailForwarding> efs = ea.getEnabledEmailForwardings();
		List<EmailListAddress> elas = ea.getEnabledEmailListAddresses();
		List<EmailPipeAddress> epas = ea.getEnabledEmailPipeAddresses();
		List<LinuxAccAddress> laas = ea.getLinuxAccAddresses();

		String tieUsername;

		if(
			bea != null
			&& efs.isEmpty()
			&& elas.isEmpty()
			&& epas.isEmpty()
			&& laas.isEmpty()
		) {
			// 1) /dev/null only
			tieUsername = devNullUsername[0];
			if(tieUsername == null) {
				tieUsername = getTieUsername(usernamesUsed);
				devNullUsername[0] = tieUsername;
				aliasesOut.print(tieUsername).println(": /dev/null");
			}
		} else if(
			efs.size() == 1
			&& elas.isEmpty()
			&& epas.isEmpty()
			&& laas.isEmpty()
		) {
			// 2) One forwarding destination, BEA ignored (use singleForwardingTies)
			String destination = efs.get(0).getDestination();
			tieUsername = singleForwardingTies.get(destination);
			if(tieUsername == null) {
				tieUsername = getTieUsername(usernamesUsed);
				singleForwardingTies.put(destination, tieUsername);
				aliasesOut.print(tieUsername).print(": ").println(destination);
			}
		} else if(
			efs.isEmpty()
			&& elas.size() == 1
			&& epas.isEmpty()
			&& laas.isEmpty()
		) {
			// 3)  One email list, BEA ignored (use singleListTies)
			UnixPath path = elas.get(0).getEmailList().getPath();
			tieUsername = singleListTies.get(path);
			if(tieUsername == null) {
				tieUsername = getTieUsername(usernamesUsed);
				singleListTies.put(path, tieUsername);
				aliasesOut.print(tieUsername).print(": :include:").println(path);
			}
		} else if(
			efs.isEmpty()
			&& elas.isEmpty()
			&& epas.size() == 1
			&& laas.isEmpty()
		) {
			// 4) One pipe, BEA ignored (use singlePipeTies)
			String command = epas.get(0).getEmailPipe().getCommand();
			tieUsername = singlePipeTies.get(command);
			if(tieUsername == null) {
				tieUsername = getTieUsername(usernamesUsed);
				singlePipeTies.put(command, tieUsername);
				aliasesOut.print(tieUsername).print(": \"| ").print(command).println('"');
			}
		} else if(
			efs.isEmpty()
			&& elas.isEmpty()
			&& epas.isEmpty()
			&& laas.size() == 1
		) {
			// 5) One Inbox only, BEA ignored (use singleInboxTies)
			LinuxServerAccount lsa = laas.get(0).getLinuxServerAccount();
			if(lsa != null) {
				Username un = lsa.getLinuxAccount().getUsername();
				if(un != null) {
					UserId username = un.getUsername();
					tieUsername = singleInboxTies.get(username);
					if(tieUsername == null) {
						tieUsername = getTieUsername(usernamesUsed);
						singleInboxTies.put(username, tieUsername);
						aliasesOut.print(tieUsername).print(": \\").println(StringUtility.replace(username.toString(), '@', "\\@"));
					}
				} else {
					tieUsername = null;
				}
			} else {
				tieUsername = null;
			}
		} else if(
			!efs.isEmpty()
			|| !elas.isEmpty()
			|| !epas.isEmpty()
			|| !laas.isEmpty()
		) {
			// 6) Multiple destinations, BEA ignored (list each)
			tieUsername = getTieUsername(usernamesUsed);
			aliasesOut.print(tieUsername).print(": ");
			boolean done = false;
			for(EmailForwarding ef : efs) {
				if(done) aliasesOut.print(",\n\t");
				else done = true;
				aliasesOut.print(ef.getDestination());
			}
			for(EmailListAddress ela : elas) {
				if(done) aliasesOut.print(",\n\t");
				else done = true;
				aliasesOut.print(":include:").print(ela.getEmailList().getPath());
			}
			for(EmailPipeAddress epa : epas) {
				if(done) aliasesOut.print(",\n\t");
				else done = true;
				aliasesOut.print("\"| ").print(epa.getEmailPipe().getCommand()).print('"');
			}
			for(LinuxAccAddress laa : laas) {
				if(done) aliasesOut.print(",\n\t");
				else done = true;
				aliasesOut.print('\\').print(StringUtility.replace(laa.getLinuxServerAccount().getLinuxAccount().getUsername().getUsername().toString(),'@',"\\@"));
			}
			aliasesOut.println();
		} else {
			// 7) Not used - ignore
			tieUsername = null;
		}

		if(tieUsername != null) usersOut.print(address).print('@').print(domain).print('\t').println(tieUsername);
	}

	private static final int TIE_USERNAME_DIGITS = 12;
	private static final String TIE_PREFIX = "tmp_";
	private static final String TIE_CHARS = "0123456789abcdefghijklmnopqrstuvwxyz";

	private static String getTieUsername(Set<String> usernamesUsed) {
		Random random = AOServDaemon.getRandom();
		StringBuilder SB = new StringBuilder(4 + TIE_USERNAME_DIGITS);
		SB.append(TIE_PREFIX);
		while(true) {
			SB.setLength(TIE_PREFIX.length());
			for(int c = 0; c < TIE_USERNAME_DIGITS; c++) {
				SB.append(
					TIE_CHARS.charAt(
						random.nextInt(TIE_CHARS.length())
					)
				);
			}
			String username = SB.toString();
			if(usernamesUsed.add(username)) {
				return username;
			}
		}
	}

	private static final Object makeMapLock = new Object();
	private static void makeMap() throws IOException, SQLException {
		synchronized(makeMapLock) {
			// Run the command
			String makemap;
			OperatingSystemVersion osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
			) {
				makemap = "/usr/sbin/makemap";
			} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			String[] cmd = { makemap, "hash", userTable.getPath() };
			Process P = Runtime.getRuntime().exec(cmd);
			try {
				try (
					InputStream in = new FileInputStream(userTable.getPath());
					OutputStream out = P.getOutputStream()
				) {
					IoUtils.copy(in, out);
				}
			} finally {
				// Wait for the process to complete
				try {
					int retCode = P.waitFor();
					if(retCode!=0) throw new IOException("Non-zero return status: "+retCode);
				} catch (InterruptedException err) {
					InterruptedIOException ioErr = new InterruptedIOException();
					ioErr.initCause(err);
					throw ioErr;
				}
			}

			// Check for error exit code
			int exit = P.exitValue();
			if (exit != 0) throw new IOException("Non-zero exit status: " + exit);
		}
	}

	public static void start() throws IOException, SQLException {
		AOServer thisAOServer = AOServDaemon.getThisAOServer();
		OperatingSystemVersion osv = thisAOServer.getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();

		synchronized(System.out) {
			if(
				// Nothing is done for these operating systems
				osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
				&& osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
				// Check config after OS check so config entry not needed
				&& AOServDaemonConfiguration.isManagerEnabled(EmailAddressManager.class)
				&& emailAddressManager == null
			) {
				System.out.print("Starting EmailAddressManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					emailAddressManager = new EmailAddressManager();
					conn.getEmailDomains().addTableListener(emailAddressManager, 0);
					conn.getBlackholeEmailAddresses().addTableListener(emailAddressManager, 0);
					conn.getEmailAddresses().addTableListener(emailAddressManager, 0);
					conn.getEmailForwardings().addTableListener(emailAddressManager, 0);
					conn.getEmailLists().addTableListener(emailAddressManager, 0);
					conn.getEmailListAddresses().addTableListener(emailAddressManager, 0);
					conn.getEmailPipes().addTableListener(emailAddressManager, 0);
					conn.getEmailPipeAddresses().addTableListener(emailAddressManager, 0);
					conn.getLinuxServerAccounts().addTableListener(emailAddressManager, 0);
					conn.getLinuxAccAddresses().addTableListener(emailAddressManager, 0);
					conn.getPackages().addTableListener(emailAddressManager, 0);
					conn.getSystemEmailAliases().addTableListener(emailAddressManager, 0);
					PackageManager.addPackageListener(emailAddressManager);
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	private static final Object newAliasesLock = new Object();
	private static void newAliases() throws IOException {
		synchronized(newAliasesLock) {
			// Run the command
			AOServDaemon.exec("/usr/bin/newaliases");
		}
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild Email Addresses";
	}
}
