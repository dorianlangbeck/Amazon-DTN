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
package br.ufpa.adtn.android.clayer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import br.ufpa.adtn.android.clayer.BtConvergenceLayer.BtAdapter;
import br.ufpa.adtn.android.clayer.BtConvergenceLayer.BtConnection;
import br.ufpa.adtn.android.util.DiscoveryManager;
import br.ufpa.adtn.android.util.DiscoveryManager.DiscoveryListener;
import br.ufpa.adtn.bundle.Bundle;
import br.ufpa.adtn.core.BPAgent;
import br.ufpa.adtn.core.ConvergenceLayer;
import br.ufpa.adtn.core.EID;
import br.ufpa.adtn.util.ChainOfSegments;
import br.ufpa.adtn.util.DataBlock;
import br.ufpa.adtn.util.Logger;
import br.ufpa.adtn.util.Properties;

public class BtConvergenceLayer extends ConvergenceLayer<BtAdapter, BtConnection> {
	private static final String DEFAULT_DISCOVERY_UUID = "c20fdacd-ecad-4463-9a9d-560512c4724a";
	private static final String DEFAULT_SERVICE_UUID = "99d5ab9c-3e69-4488-9e81-3a9e993a8c52";
	private static final Logger LOGGER = new Logger("BluetoothConvergenceLayer");
	private static final short BUNDLE_HEADER = (short) 0x8A2D;
	private static final short MAGIC_HEADER = (short) 0x4E10;
	

	@Override
	protected BtAdapter createAdapter(Properties configuration, Object data) {
		if (data == null || !(data instanceof Context))
			throw new IllegalArgumentException();
		
		try {
			final String discovery = configuration.getString("discovery-uuid", DEFAULT_DISCOVERY_UUID);
			final String service = configuration.getString("service-uuid", DEFAULT_SERVICE_UUID);
			
			// TODO Revert
//			final String local_eid = configuration.getProperty("local-eid");
//			if (local_eid == null)
//				throw new NullPointerException();
			
			return new BtAdapter(
					(Context) data,
					BPAgent.getHostEID().toString(),
					UUID.fromString(discovery),
					UUID.fromString(service)
			);
		} catch (IOException e) {
			LOGGER.e("Adapter creation failure", e);
			return null;
		}
	}
	
	
	
	public class BtAdapter extends ConvergenceLayer<BtAdapter, BtConnection>.AbstractAdapter {
		private BluetoothServerSocket sSocket;
		private final BtDiscovery discovery;
		private final UUID uuid;
		
		private BtAdapter(Context context, String local_eid, UUID discovery, UUID uuid) throws IOException {
			this.discovery = new BtDiscovery(this, context, local_eid, discovery, uuid);
			this.sSocket = null;
			this.uuid = uuid;
			
			setupDiscovery(this.discovery);
		}
		
		@Override
		protected BtConnection accept() {
			try {
				return new BtConnection(this, sSocket.accept());
			} catch (Exception e) {
				return null;
			}
		}

		@Override
		protected void doPreparations() throws Throwable {
			sSocket = BluetoothAdapter.getDefaultAdapter().listenUsingInsecureRfcommWithServiceRecord("BtAdapter", uuid);
		}

		@Override
		protected void doFinalizations() {
			if (sSocket != null) {
				try {
					sSocket.close();
				} catch (Exception e) { }
				
				sSocket = null;
			}
		}
		
	}
	
	public class BtConnection extends ConvergenceLayer<BtAdapter, BtConnection>.AbstractConnection {
		private final Logger LOGGER = new Logger("BtConnecion");

		private final BlockingQueue<Bundle> outputBundles;
		private final BluetoothDevice device;
		private final UUID uuid;
		
		private BluetoothSocket socket;
		
		{
			outputBundles = new LinkedBlockingQueue<Bundle>();
		}
		
		private BtConnection(BtAdapter adapter, EID eid, BluetoothDevice device, UUID uuid) {
			super(adapter, eid);
			this.device = device;
			this.uuid = uuid;
			register(eid);
		}
		
		private BtConnection(BtAdapter adapter, BluetoothSocket socket) throws IllegalStateException, IllegalArgumentException, IOException {
			super(adapter);
			this.device = socket.getRemoteDevice();
			this.socket = socket;
			this.uuid = null;
			
			setupStream(
					new BufferedOutputStream(socket.getOutputStream()),
					new BufferedInputStream(socket.getInputStream())
			);
		}
		
		@Override
		public void send(Bundle bundle) {
			outputBundles.offer(bundle);
		}

		@Override
		protected void processOutput(OutputStream out) throws IOException {
			Bundle bundle = null;
			try {
				final DataOutputStream dos = new DataOutputStream(out);
				dos.writeShort(MAGIC_HEADER);
				
				//FIXME Each ConvergenceLayer must have your own EID (if needed)
				dos.writeUTF(BPAgent.getHostEID().toString());
				dos.flush();
				
				while (!Thread.interrupted()) {
					// Buffer allocation need get smarter
					final ByteBuffer buffer = ByteBuffer.allocate(0x10000);
					final ChainOfSegments chain = new ChainOfSegments();
					
					bundle = outputBundles.take();
					notifyTransferStarted(bundle);
					
					bundle.serialize(chain, buffer);
					final DataBlock block = DataBlock.join(chain.getSegments());
					final int bLength = block.getLength();

					dos.writeShort(BUNDLE_HEADER);
					dos.writeInt(bLength);
					block.copy(dos);
					dos.flush();
					
					notifyTransferred(bundle);
					bundle = null;
				}
			} catch (InterruptedException e) {
				LOGGER.w("Output Interrupted");
			} catch (Throwable t) {
				LOGGER.e("Output error", t);
			} finally {
				LOGGER.d("EXITING(processOutput)");
				
				if (bundle != null)
					notifyTransferAborted(bundle);
			}
		}

		@Override
		protected void processInput(InputStream in) throws IOException {
			final DataInputStream dis = new DataInputStream(in);
			if (dis.readShort() != MAGIC_HEADER)
				throw new IOException("Wrong magic");
			
			final EID remote_eid = EID.get(dis.readUTF());
			if (!isRegistered())
				register(remote_eid);
			
			try {
				while (isConnected()) {
					if (dis.readShort() != BUNDLE_HEADER)
						throw new IOException("Wrong header");
					
					try {
						final int l = dis.readInt();
						final byte[] b = new byte[l];
						for (int r = 0, p = 0; (r = dis.read(b, p, l - p)) != -1 && r < l; p += r);
						
						notifyReceived(new Bundle(ByteBuffer.wrap(b)));
					} catch (IOException e) {
						LOGGER.w("Connection failure");
						break;
					}
				}
			} finally {
				LOGGER.d("EXITING(processInput)");
			}
		}

		@Override
		protected void openConnection() throws IOException {
			socket = device.createInsecureRfcommSocketToServiceRecord(uuid);
			socket.connect();
			setupStream(
					new BufferedOutputStream(socket.getOutputStream()),
					new BufferedInputStream(socket.getInputStream())
			);
		}

		@Override
		protected void closeConnection() {
			if (socket != null)
				try {
					socket.close();
				} catch (Exception e) { }
		}
	}
	
	public class BtDiscovery implements IDiscovery, DiscoveryListener {
		private final DiscoveryManager.Service service;
		private final BtAdapter adapter;
		private boolean running;
		
		private BtDiscovery(BtAdapter adapter, Context context, String local_eid, UUID discovery_uuid, UUID service_uuid) throws IOException {
			service = DiscoveryManager.createService(
					context,
					local_eid,
					discovery_uuid,
					service_uuid,
					this
			);
			
			this.adapter = adapter;
		}

		@Override
		public void start() throws Throwable {
			service.start();
			running = true;
		}

		@Override
		public boolean isRunning() {
			return running;
		}

		@Override
		public void stop() {
			running = false;
			service.stop();
		}

		@Override
		public void notifyNeighborFound(String eid, BluetoothDevice device, UUID uuid) {
			notifyConnectionDiscovered(new BtConnection(
					adapter,
					EID.get(eid),
					device,
					uuid
			));
		}
	}
}
