/**
 * Amazon-DTN - Lightweight Delay Tolerant Networking Implementation
 * Copyright (C) 2013  Dórian C. Langbeck
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package br.ufpa.dtns;

import java.lang.reflect.Constructor;
import java.net.SocketAddress;
import java.net.URLClassLoader;
import java.rmi.AccessException;
import java.rmi.AlreadyBoundException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;
import java.util.Map;

import br.ufpa.adtn.bundle.Bundle;
import br.ufpa.adtn.core.EID;
import br.ufpa.adtn.util.Logger.Priority;
import br.ufpa.dtns.util.CachedByteCode;
import br.ufpa.dtns.util.CachedClassLoader;
import br.ufpa.dtns.util.ClassTransformer;

public final class DeviceLoader {
	private static RegisterNotifier.Register REGISTER;
	private static final Map<String, LocalDevice> MAP;
	private static final CachedByteCode CBC;
	private static boolean CONFIGURED;
	private static Registry REGISTRY;
	private static Priority PRIORITY;
	private static int PORT;
	
	static {
		MAP = new HashMap<String, LocalDevice>();
		PRIORITY = Priority.VERBOSE;
		CBC = new CachedByteCode();
		
		final URLClassLoader ucl = (URLClassLoader) ClassLoader.getSystemClassLoader();
		try {
			CBC.load(ucl.getURLs());
		} catch (Throwable t) {
			if (t instanceof RuntimeException)
				throw (RuntimeException) t;
			
			if (t instanceof Error)
				throw (Error) t;
			
			throw new RuntimeException(t);
		}
	}
	
	public static void setDefaultPriority(Priority priority) {
		PRIORITY = priority;
	}
	
	public static Priority getDefaultPriority() {
		return PRIORITY;
	}
	
	public synchronized static void setTransformer(ClassTransformer transformer) {
		CBC.updateTransformer(transformer);
	}
	
	public static CachedByteCode getCachedByteCode() {
		return CBC;
	}
	
	public static LocalDevice create(String hostname) throws AccessException, SecurityException, RemoteException, NotBoundException {
		return create(hostname, PRIORITY);
	}
	
	public synchronized static LocalDevice create(String hostname, Priority priority) throws AccessException, SecurityException, RemoteException, NotBoundException {
		if (!EID.isValidHostname(hostname))
			throw new IllegalArgumentException("Illegal hostname: " + hostname);
		
		if (MAP.containsKey(hostname))
			throw new IllegalArgumentException("Name already registered");
		
		final LocalDevice device = new LocalDevice(
				EID.forHost(hostname),
				createDevice(hostname),
				priority
		);
		MAP.put(hostname, device);
		return device;
	}
	
	public synchronized static LocalDevice get(String name) {
		return MAP.get(name);
	}
	
	private static Class<?> loadUnique(Class<?> oClass, boolean initialize) throws ClassNotFoundException {
		return Class.forName(
				oClass.getName(),
				initialize,
				new CachedClassLoader(ClassLoader.getSystemClassLoader(), CBC)
		);
	}
	
	public synchronized static void init(int port) throws RemoteException, AlreadyBoundException {
		if (CONFIGURED)
			throw new IllegalStateException();
		
		REGISTRY = LocateRegistry.createRegistry(port);
		PORT = port;
		
		REGISTER = new RegisterNotifier.Register();
		REGISTRY.bind("register", UnicastRemoteObject.exportObject(
				REGISTER,
				PORT
		));
		
		CONFIGURED = true;
	}
	
	private static DeviceConnector createDevice(String eid) throws SecurityException, AccessException, RemoteException, NotBoundException {
		REGISTER.register(null);
		
		try {
			final Constructor<?> constructor = loadUnique(RemoteDevice.class, true)
					.getDeclaredConstructor(String.class, int.class);
			
			constructor.setAccessible(true);
			constructor.newInstance(eid, PORT);
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		
		final String name = REGISTER.getName();
		if (name == null)
			return null;
		
		return (DeviceConnector) REGISTRY.lookup(name);
	}
	
	
	public static final class LocalDevice {
		private final DeviceConnector connector;
		private final Priority priority;
		private final EID eid;
		
		private LocalDevice(EID eid, DeviceConnector connector, Priority priority) throws RemoteException {
			this.connector = connector;
			this.priority = priority;
			this.eid = eid;
		}
		
		public void init(String config, String simulation) throws RemoteException {
			connector.init(priority, config, simulation);
		}
		
		public void discovery(LocalDevice device) throws RemoteException {
			connector.discovery(device.eid.toString(), device.getAddress());
		}
		
		public void addBundle(Bundle bundle) throws RemoteException {
			connector.addBundle(bundle);
		}
		
		public SocketAddress getAddress() throws RemoteException {
			return connector.getAddress();
		}

		public DeviceConnector getConnector() {
			return connector;
		}
		
		public EID getEID() {
			return eid;
		}
	}
	
	
	private DeviceLoader() { }
}
