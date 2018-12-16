/*
 * Copyright 2003-2013, 2015, 2016, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.net.xinetd;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.Group;
import com.aoindustries.aoserv.client.linux.GroupServer;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.linux.User;
import com.aoindustries.aoserv.client.linux.UserServer;
import com.aoindustries.aoserv.client.net.AppProtocol;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.client.net.TcpRedirect;
import com.aoindustries.aoserv.client.scm.CvsRepository;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.email.ImapManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.net.Port;
import com.aoindustries.net.Protocol;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * Handles the building of xinetd configs and files.
 */
public final class XinetdManager extends BuilderThread {

	/**
	 * The type used for UNLISTED services.
	 */
	public static final String UNLISTED="UNLISTED";

	private static XinetdManager xinetdManager;

	public static final File xinetdDirectory=new File("/etc/xinetd.d");

	private XinetdManager() {
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			AOServConnector connector=AOServDaemon.getConnector();
			Server aoServer=AOServDaemon.getThisAOServer();
			OperatingSystemVersion osv = aoServer.getServer().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId != OperatingSystemVersion.MANDRIVA_2006_0_I586
				&& osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			// Reused on inner loops
			final ByteArrayOutputStream bout = new ByteArrayOutputStream();

			synchronized(rebuildLock) {
				UserServer interbaseUser=aoServer.getLinuxServerAccount(User.INTERBASE);
				UserServer nobodyUser=aoServer.getLinuxServerAccount(User.NOBODY);
				UserServer rootUser=aoServer.getLinuxServerAccount(User.ROOT);
				GroupServer ttyGroup=aoServer.getLinuxServerGroup(Group.TTY);

				// Build a list of services that should be running
				List<Bind> binds=aoServer.getServer().getNetBinds();
				List<Service> services=new ArrayList<>(binds.size()+(ImapManager.WUIMAP_CONVERSION_ENABLED ? 1 : 0)); // Worst-case all binds are in xinetd

				if(ImapManager.WUIMAP_CONVERSION_ENABLED) {
					// Remove once conversion to CentOS has been completed
					services.add(
						new Service(
							UNLISTED,
							-1,
							-1,
							null,
							null,
							null,
							"wuimap",
							Protocol.TCP,
							aoServer.getPrimaryIPAddress(),
							Port.valueOf(8143, Protocol.TCP),
							false,
							rootUser,
							null,
							"/opt/imap-2007d/bin/imapd",
							null,
							null,
							"HOST DURATION",
							"HOST USERID",
							-1,
							null,
							null
						)
					);
				}

				for (Bind bind : binds) {
					Port port=bind.getPort();
					TcpRedirect redirect=bind.getNetTcpRedirect();
					AppProtocol protocolObj=bind.getAppProtocol();
					String protocol=protocolObj.getProtocol();
					if(
						redirect!=null
						//|| protocol.equals(AppProtocolAUTH)
						|| protocol.equals(AppProtocol.CVSPSERVER)
						|| protocol.equals(AppProtocol.NTALK)
						|| protocol.equals(AppProtocol.TALK)
						|| protocol.equals(AppProtocol.TELNET)
						|| (
							// POP and IMAP is handled through xinetd on Mandriva 2006.0
							osvId==OperatingSystemVersion.MANDRIVA_2006_0_I586
							&& (
								//protocol.equals(AppProtocol.POP2)
								protocol.equals(AppProtocol.POP3)
								|| protocol.equals(AppProtocol.SIMAP)
								|| protocol.equals(AppProtocol.SPOP3)
								|| protocol.equals(AppProtocol.IMAP2)
							)
						) || (
							// FTP is handled through xinetd on CentOS 5
							osvId==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
							&& protocol.equals(AppProtocol.FTP)
						)
					) {
						Service service;
						if(redirect!=null) {
							Protocol netProtocol=port.getProtocol();
							if(netProtocol != Protocol.TCP) throw new SQLException("Only TCP ports may be redirected: (net_binds.pkey="+bind.getPkey()+").protocol="+netProtocol);

							service=new Service(
								UNLISTED,
								-1,
								-1,
								redirect.getConnectionsPerSecond()+" "+redirect.getConnectionsPerSecondOverloadSleepTime(),
								null,
								null,
								"redirect",
								netProtocol,
								bind.getIpAddress(),
								port,
								false,
								rootUser,
								null,
								null,
								null,
								null,
								null,
								null,
								-1,
								null,
								redirect.getDestinationHost().toString()+" "+redirect.getDestinationPort().getPort()
							);
						} else {
							boolean portMatches=protocolObj.getPort().equals(port);
							/*if(protocol.equals(AppProtocol.AUTH)) {
							service=new Service(
							portMatches?null:UNLISTED,
							-1,
							null,
							null,
							portMatches?"auth":"auth-unlisted",
							bind.getNetProtocol(),
							bind.getInetAddress(),
							portMatches?null:port,
							true,
							rootUser,
							null,
							"/usr/sbin/in.identd",
							"-w -e",
							null,
							null,
							-1,
							null,
							null
							);
							} else */
							switch (protocol) {
								case AppProtocol.CVSPSERVER:
									List<CvsRepository> repos=aoServer.getCvsRepositories();
									if(osvId==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
										StringBuilder server_args=new StringBuilder();
										for(int d=0;d<repos.size();d++) {
											CvsRepository repo=repos.get(d);
											if(d>0) server_args.append(' ');
											server_args.append("--allow-root=").append(repo.getPath());
										}
										if(!repos.isEmpty()) server_args.append(' ');
										server_args.append("-f pserver");
										service=new Service(
											portMatches?null:UNLISTED,
											-1,
											-1,
											"100 30",
											null,
											"REUSE",
											portMatches?"cvspserver":"cvspserver-unlisted",
											port.getProtocol(),
											bind.getIpAddress(),
											portMatches?null:port,
											false,
											rootUser,
											null,
											"/usr/bin/cvs",
											null,
											server_args.toString(),
											"HOST DURATION",
											"HOST USERID",
											-1,
											null,
											null
										);
									} else if(osvId==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
										StringBuilder server_args=new StringBuilder();
										server_args.append("-f");
										for(CvsRepository repo : repos) {
											server_args.append(" --allow-root=").append(repo.getPath());
										}
										server_args.append(" pserver");
										service=new Service(
											portMatches?null:UNLISTED,
											-1,
											-1,
											"100 30",
											null,
											"REUSE",
											portMatches?"cvspserver":"cvspserver-unlisted",
											port.getProtocol(),
											bind.getIpAddress(),
											portMatches?null:port,
											false,
											rootUser,
											null,
											"/usr/bin/cvs",
											"HOME=" + CvsRepository.DEFAULT_CVS_DIRECTORY,
											server_args.toString(),
											"HOST DURATION",
											"HOST USERID",
											-1,
											null,
											null
										);
									} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
									break;
								case AppProtocol.FTP:
									if(osvId==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
										service=new Service(
											portMatches?null:UNLISTED,
											100,
											20, // Was 5, but confsys03 on www7.fc.aoindustries.com hit this limit on 2009-07-31
											"100 30",
											"/etc/vsftpd/busy_banner",
											"IPv4",
											portMatches?"ftp":"ftp-unlisted",
											port.getProtocol(),
											bind.getIpAddress(),
											portMatches?null:port,
											false,
											rootUser,
											null,
											"/usr/sbin/vsftpd",
											null,
											"/etc/vsftpd/vhosts/vsftpd_"+bind.getIpAddress().getInetAddress().toString()+"_"+port.getPort()+".conf",
											"PID HOST DURATION",
											"HOST",
											10,
											null,
											null
										);
									} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
									break;
								case AppProtocol.IMAP2:
									if(osvId==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
										service=new Service(
											portMatches?null:UNLISTED,
											-1,
											-1,
											"100 30",
											null,
											null,
											portMatches?"imap":"imap-unlisted",
											port.getProtocol(),
											bind.getIpAddress(),
											portMatches?null:port,
											false,
											rootUser,
											null,
											"/usr/sbin/imapd",
											null,
											null,
											"HOST DURATION",
											"HOST USERID",
											-1,
											null,
											null
										);
									} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
									break;
								case AppProtocol.NTALK:
									if(osvId==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
										service=new Service(
											portMatches?null:UNLISTED,
											-1,
											-1,
											null,
											null,
											null,
											portMatches?"ntalk":"ntalk-unlisted",
											port.getProtocol(),
											bind.getIpAddress(),
											portMatches?null:port,
											true,
											nobodyUser,
											ttyGroup,
											"/usr/sbin/in.ntalkd",
											null,
											null,
											"HOST DURATION",
											"HOST USERID",
											-1,
											null,
											null
										);
									} else if(osvId==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
										service=new Service(
											portMatches?null:UNLISTED,
											-1, // instances
											-1, // per_source
											null, // cps
											null, // banner_fail
											"IPv4", // flags
											portMatches?"ntalk":"ntalk-unlisted",
											port.getProtocol(),
											bind.getIpAddress(),
											portMatches?null:port,
											true,
											nobodyUser,
											ttyGroup,
											"/usr/sbin/in.ntalkd",
											null, // env
											null, // server_args
											"HOST DURATION",
											"HOST USERID",
											-1,
											null,
											null
										);
									} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
									/*} else if(protocol.equals(AppProtocol.POP2)) {
									if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
									service=new Service(
									portMatches?null:UNLISTED,
									-1,
									-1,
									"100 30",
									null,
									null,
									portMatches?"pop2":"pop2-unlisted",
									bind.getNetProtocol(),
									bind.getInetAddress(),
									portMatches?null:port,
									false,
									rootUser,
									null,
									"/usr/sbin/ipop2d",
									null,
									null,
									"HOST DURATION",
									"HOST USERID",
									-1,
									null,
									null
									);
									} else throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);*/
									break;
								case AppProtocol.POP3:
									if(osvId==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
										service=new Service(
											portMatches?null:UNLISTED,
											-1,
											-1,
											"100 30",
											null,
											null,
											portMatches?"pop3":"pop3-unlisted",
											port.getProtocol(),
											bind.getIpAddress(),
											portMatches?null:port,
											false,
											rootUser,
											null,
											"/usr/sbin/ipop3d",
											null,
											null,
											"HOST DURATION",
											"HOST USERID",
											-1,
											null,
											null
										);
									} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
									break;
								case AppProtocol.SIMAP:
									if(osvId==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
										service=new Service(
											portMatches?null:UNLISTED,
											-1,
											-1,
											"100 30",
											null,
											null,
											portMatches?"imaps":"imaps-unlisted",
											port.getProtocol(),
											bind.getIpAddress(),
											portMatches?null:port,
											false,
											rootUser,
											null,
											"/usr/sbin/imapsd",
											null,
											null,
											"HOST DURATION",
											"HOST USERID",
											-1,
											null,
											null
										);
									} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
									break;
								case AppProtocol.SPOP3:
									if(osvId==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
										service=new Service(
											portMatches?null:UNLISTED,
											-1,
											-1,
											"100 30",
											null,
											null,
											portMatches?"pop3s":"pop3s-unlisted",
											port.getProtocol(),
											bind.getIpAddress(),
											portMatches?null:port,
											false,
											rootUser,
											null,
											"/usr/sbin/ipop3sd",
											null,
											null,
											"HOST DURATION",
											"HOST USERID",
											-1,
											null,
											null
										);
									} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
									break;
								case AppProtocol.TALK:
									if(osvId==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
										service=new Service(
											portMatches?null:UNLISTED,
											-1,
											-1,
											null,
											null,
											null,
											portMatches?"talk":"talk-unlisted",
											port.getProtocol(),
											bind.getIpAddress(),
											portMatches?null:port,
											true,
											nobodyUser,
											ttyGroup,
											"/usr/sbin/in.talkd",
											null,
											null,
											"HOST DURATION",
											"HOST USERID",
											-1,
											null,
											null
										);
									} else if(osvId==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
										service=new Service(
											portMatches?null:UNLISTED,
											-1, // instances
											-1, // per_source
											null, // cps
											null, // banner_fail
											"IPv4", // flags
											portMatches?"talk":"talk-unlisted",
											port.getProtocol(),
											bind.getIpAddress(),
											portMatches?null:port,
											true,
											nobodyUser,
											ttyGroup,
											"/usr/sbin/in.talkd",
											null, // env
											null, // server_args
											"HOST DURATION",
											"HOST USERID",
											-1,
											null,
											null
										);
									} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
									break;
								case AppProtocol.TELNET:
									if(osvId==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
										service=new Service(
											portMatches?null:UNLISTED,
											-1,
											-1,
											"100 30",
											null,
											"REUSE",
											portMatches?"telnet":"telnet-unlisted",
											port.getProtocol(),
											bind.getIpAddress(),
											portMatches?null:port,
											false,
											rootUser,
											null,
											"/usr/sbin/telnetd",
											null,
											"-a none",
											"HOST DURATION",
											"HOST USERID",
											-1,
											null,
											null
										);
									} else if(osvId==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
										service=new Service(
											portMatches?null:UNLISTED,
											-1,
											-1,
											"100 30",
											null,
											"REUSE",
											portMatches?"telnet":"telnet-unlisted",
											port.getProtocol(),
											bind.getIpAddress(),
											portMatches?null:port,
											false,
											rootUser,
											null,
											"/usr/sbin/in.telnetd",
											null,
											null,
											"HOST DURATION",
											"HOST USERID",
											-1,
											null,
											null
										);
									} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
									break;
								default:
									throw new RuntimeException("Unexpected protocol: "+protocol);
							}
						}

						// Do not add if is a duplicate ip address, net protocol, and port
						boolean foundDup=false;
						for(Service other : services) {
							if(service.bindMatches(other)) {
								foundDup=true;
								break;
							}
						}
						if(!foundDup) services.add(service);
					}
				}

				boolean needsReloaded=false;

				// (Re)build configs to match service list
				Set<String> filenames = new HashSet<>();
				final int numServices=services.size();
				for(int c=0; c<numServices; c++) {
					Service service = services.get(c);
					String desiredFilename = service.getService();
					String filename = null;
					for(int d=1; d<Integer.MAX_VALUE; d++) {
						String checkFilename = d==1 ? desiredFilename : (desiredFilename+"-"+d);
						if(!filenames.contains(checkFilename)) {
							filename = checkFilename;
							break;
						}
					}
					if(filename==null) throw new IOException("Unable to find available filename for service: "+desiredFilename);
					filenames.add(filename);

					// Build to RAM first
					bout.reset();
					try (ChainWriter out = new ChainWriter(bout)) {
						service.printXinetdConfig(out);
					}
					byte[] newBytes = bout.toByteArray();

					// Move into place if different than existing
					UnixFile existingUF=new UnixFile(xinetdDirectory, filename);
					if(
						!existingUF.getStat().exists()
						|| !existingUF.contentEquals(newBytes)
					) {
						UnixFile newUF = new UnixFile(xinetdDirectory, filename+".new");
						try (OutputStream newOut = new FileOutputStream(newUF.getFile())) {
							newUF.setMode(0600);
							newOut.write(newBytes);
						}
						newUF.renameTo(existingUF);
						needsReloaded = true;
					}
				}

				// Cleanup extra configs
				String[] list = xinetdDirectory.list();
				if(list!=null) {
					for (String filename : list) {
						if(!filenames.contains(filename)) {
							new UnixFile(xinetdDirectory, filename).delete();
							needsReloaded=true;
						}
					}
				}

				// Control service
				UnixFile rcFile=new UnixFile("/etc/rc.d/rc3.d/S56xinetd");
				if(numServices==0) {
					// Turn off xinetd completely if not already off
					if(rcFile.getStat().exists()) {
						// Stop service
						AOServDaemon.exec("/etc/rc.d/init.d/xinetd", "stop");
						// Disable with chkconfig
						if(osvId==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
							AOServDaemon.exec("/sbin/chkconfig", "--del", "xinetd");
						} else if(osvId==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
							AOServDaemon.exec("/sbin/chkconfig", "xinetd", "off");
						} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
					}
				} else {
					// Turn on xinetd if not already on
					if(!rcFile.getStat().exists()) {
						// Enable with chkconfig
						if(osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586) {
							AOServDaemon.exec("/sbin/chkconfig", "--add", "xinetd");
						} else if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
							AOServDaemon.exec("/sbin/chkconfig", "xinetd", "on");
						} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
						// Start service
						AOServDaemon.exec("/etc/rc.d/init.d/xinetd", "start");
					} else {
						// Reload xinetd if modified
						if(needsReloaded) {
							// Try reload config first
							/* reload has several limitations documented in the man page for xinetd.conf, will always stop/start instead
							try {
								AOServDaemon.exec(
									new String[] {
										"/etc/rc.d/init.d/xinetd",
										"reload"
									}
								);
							} catch(IOException err) {
								LogFactory.getLogger(this.getClass()).log(Level.SEVERE, null, err);*/

								// Try more forceful stop/start
								try {
									AOServDaemon.exec(
										"/etc/rc.d/init.d/xinetd",
										"stop"
									);
								} catch(IOException err2) {
									LogFactory.getLogger(this.getClass()).log(Level.SEVERE, null, err2);
								}
								try {
									Thread.sleep(1000);
								} catch(InterruptedException err2) {
									LogFactory.getLogger(this.getClass()).log(Level.WARNING, null, err2);
								}
								AOServDaemon.exec(
									"/etc/rc.d/init.d/xinetd",
									"start"
								);
							//}
						}
					}
				}
			}
			return true;
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			LogFactory.getLogger(XinetdManager.class).log(Level.SEVERE, null, T);
			return false;
		}
	}

	public static void start() throws IOException, SQLException {
		Server thisAOServer = AOServDaemon.getThisAOServer();
		OperatingSystemVersion osv = thisAOServer.getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();

		synchronized(System.out) {
			if(
				// Nothing is done for these operating systems
				osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
				&& osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
				// Check config after OS check so config entry not needed
				&& AOServDaemonConfiguration.isManagerEnabled(XinetdManager.class)
				&& xinetdManager == null
			) {
				System.out.print("Starting XinetdManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
					|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					xinetdManager = new XinetdManager();
					conn.getScm().getCvsRepository().addTableListener(xinetdManager, 0);
					conn.getNet().getBind().addTableListener(xinetdManager, 0);
					conn.getNet().getTcpRedirect().addTableListener(xinetdManager, 0);
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild xinetd Configuration";
	}
}
