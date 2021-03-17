package havis.middleware.reader.rf_r300;

import havis.device.io.Configuration;
import havis.device.io.Direction;
import havis.device.io.IOConfiguration;
import havis.device.io.IOConsumer;
import havis.device.io.IODevice;
import havis.device.io.StateEvent;
import havis.device.io.exception.ParameterException;
import havis.device.rf.RFConsumer;
import havis.device.rf.RFDevice;
import havis.device.rf.common.util.RFUtils;
import havis.device.rf.configuration.ConnectType;
import havis.device.rf.exception.ConnectionException;
import havis.device.rf.tag.Filter;
import havis.device.rf.tag.TagData;
import havis.device.rf.tag.operation.ReadOperation;
import havis.device.rf.tag.result.OperationResult;
import havis.device.rf.tag.result.ReadResult;
import havis.middleware.ale.base.KeyValuePair;
import havis.middleware.ale.base.exception.ImmutableReaderException;
import havis.middleware.ale.base.exception.ImplementationException;
import havis.middleware.ale.base.exception.ValidationException;
import havis.middleware.ale.base.message.Message;
import havis.middleware.ale.base.operation.port.Operation;
import havis.middleware.ale.base.operation.port.Pin;
import havis.middleware.ale.base.operation.port.Pin.Type;
import havis.middleware.ale.base.operation.port.Port;
import havis.middleware.ale.base.operation.port.PortObservation;
import havis.middleware.ale.base.operation.port.PortOperation;
import havis.middleware.ale.base.operation.port.result.Result;
import havis.middleware.ale.base.operation.port.result.Result.State;
import havis.middleware.ale.base.operation.tag.LockType;
import havis.middleware.ale.base.operation.tag.Sighting;
import havis.middleware.ale.base.operation.tag.Tag;
import havis.middleware.ale.base.operation.tag.TagOperation;
import havis.middleware.ale.base.operation.tag.result.ResultState;
import havis.middleware.ale.exit.Exits;
import havis.middleware.ale.reader.Callback;
import havis.middleware.ale.reader.Capability;
import havis.middleware.ale.reader.ImmutableReaderConnector;
import havis.middleware.ale.reader.Prefix;
import havis.middleware.ale.reader.ReaderUtils;
import havis.middleware.ale.service.rc.RCConfig;
import havis.middleware.reader.rf_r300.RF_RInventoryOperation.UserReadMode;
import havis.middleware.utils.data.Calculator;
import havis.util.monitor.AntennaConfiguration;
import havis.util.monitor.Capabilities;
import havis.util.monitor.CapabilityType;
import havis.util.monitor.ConfigurationType;
import havis.util.monitor.ConnectionError;
import havis.util.monitor.DeviceCapabilities;
import havis.util.monitor.ReaderEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TimerTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RF_R300ReaderConnector implements ImmutableReaderConnector {

	private static final Logger log = Logger.getLogger(RF_R300ReaderConnector.class.getName());

	private static final int CONNECTION_TIMEOUT = 300;

	protected Callback clientCallback;

	private RFConsumer rfConsumer;
	private RFDevice rfDevice;

	private IODevice ioDevice;
	private IOConsumer ioConsumer;

	private Map<Long, TagOperation> tagOperationList = new Hashtable<>();
	private List<Long> tagObserverList = new ArrayList<>();

	private Lock inventoryLock = new ReentrantLock();
	private Condition inventoryCondition = inventoryLock.newCondition();
	private Thread inventoryThread;

	private Object syncReader = new Object();
	private Object syncTagOperationList = new Object();
	private Object syncTagObserverList = new Object();
	private Object syncIoConnectionState = new Object();

	private Object syncPortObservationList = new Object();
	private Map<Long, PortObservation> portObservationList = new Hashtable<>();
	private Object syncPortObserverList = new Object();
	private List<Long> portObserverList = new ArrayList<>();
	private volatile boolean doObservation;

	private volatile boolean connected;
	private boolean ioConnected;
	private volatile boolean doInventory;

	private DeviceCapabilities deviceCapabilities;

	private RF_RInventoryOperation inventoryOperation;

	private boolean readerErrorOccurred;

	private int readerErrorCount;

	private Lock syncExecuteTagOperation = new ReentrantLock(true);
	private boolean executeAbort = false;
	private Semaphore executeEvent = new Semaphore(1);

	protected KeyValuePair<Long, TagOperation> executeTagOperation = new KeyValuePair<>();

	private Map<Short, StateResetTimer> pinResetTimers = new HashMap<>();

	private List<Short> antennas = new ArrayList<>();
	
	private static class RF_R300_RFConsumer implements RFConsumer {

		@SuppressWarnings("unused")
		private RF_R300ReaderConnector con;

		public RF_R300_RFConsumer(RF_R300ReaderConnector con) {
			super();
			this.con = con;
		}

		@Override
		public List<havis.device.rf.tag.operation.TagOperation> getOperations(TagData tag) {
			return null;
		}

		@Override
		public void keepAlive() {
		}

		@Override
		public void connectionAttempted() {
		}
	}

	private static class RF_R300_IOConsumer implements IOConsumer {

		private RF_R300ReaderConnector con;

		public RF_R300_IOConsumer(RF_R300ReaderConnector con) {
			super();
			this.con = con;
		}

		@Override
		public void stateChanged(StateEvent se) {
			/*
			 * if we are currently not observing, there is no need to report
			 * anything, so return.
			 */
			if (!con.doObservation)
				return;

			/* generate a port object for the observation report */
			Pin pin = new Pin(se.getId(), Type.INPUT);
			havis.middleware.ale.base.operation.port.result.ReadResult portReadRes = new havis.middleware.ale.base.operation.port.result.ReadResult(
					State.SUCCESS, se.getState() == havis.device.io.State.HIGH ? (byte) 0x01 : (byte) 0x00);
			HashMap<Integer, Result> result = new HashMap<>();
			result.put(0, portReadRes);
			Port port = new Port(pin, this.con.ioDevice.toString(), result);

			// send the observation report to all enabled observers
			con.sendObservationReport(port);
		}

		@Override
		public void connectionAttempted() {

		}

		@Override
		public void keepAlive() {

		}
	}

	/**
	 * Initializes a new instance of the
	 * Havis.Middleware.Reader.RF_RReaderConnector class.
	 */
	public RF_R300ReaderConnector(RFDevice rfDevice, IODevice ioDevice) {
		synchronized (syncReader) {
			this.inventoryOperation = new RF_RInventoryOperation();

			this.ioConsumer = new RF_R300_IOConsumer(this);
			this.rfConsumer = new RF_R300_RFConsumer(this);

			this.ioDevice = ioDevice;
			this.rfDevice = rfDevice;

			this.deviceCapabilities = new DeviceCapabilities(null, "Menucha Team", "RF-R300", null);

			antennas.add((short) 0);
		}
	}

	@Override
	public void setCallback(Callback callback) {
		this.clientCallback = callback;
		this.deviceCapabilities.setName(callback.getName());
	}

	@Override
	public void setProperties(Map<String, String> properties) throws ValidationException, ImplementationException {
		for (Entry<String, String> property : properties.entrySet()) {
			switch (property.getKey()) {
			case Prefix.Connector:
			case Prefix.Reader:
				throw new ImplementationException(new ImmutableReaderException("Setting properties is not supported by this reader."));
			}
		}
	}

	@Override
	public String getCapability(String name) throws ValidationException, ImplementationException {
		switch (name) {
		case Capability.LostEPCOnWrite:
			return "false";
		default:
			throw new ValidationException("Unkown capabilty name '" + name + "' for " + this.rfDevice + "!");
		}
	}

	private boolean hasActivePinResetTimers() {
		synchronized (syncReader) {
			for (Entry<Short, StateResetTimer> entry : this.pinResetTimers.entrySet()) {
				if (entry.getValue() != null) {
					return true;
				}
			}
		}
		return false;
	}

	private void updatePinResetTimers() {
		synchronized (syncReader) {
			List<Configuration> configuration;
			try {
				configuration = this.ioDevice.getConfiguration(havis.device.io.Type.IO, (short) 0);

				for (Configuration c : configuration) {
					IOConfiguration io = (IOConfiguration) c;
					if (io.getDirection() == Direction.OUTPUT) {
						Short id = Short.valueOf(io.getId());
						if (!this.pinResetTimers.containsKey(id)) {
							this.pinResetTimers.put(id, null);
						}
					}
				}
			} catch (Exception e) {
				log.log(Level.SEVERE, "Failed to get IO configuration.", e);
			}
		}
	}

	private void flushPinResetTimers() {
		synchronized (syncReader) {
			List<Short> cancelations = new ArrayList<>();
			for (Entry<Short, StateResetTimer> entry : this.pinResetTimers.entrySet()) {
				if (entry.getValue() != null) {
					cancelations.add(entry.getKey());
				}
			}

			for (Short id : cancelations) {
				StateResetTimer timer = this.pinResetTimers.get(id);
				this.pinResetTimers.put(id, null);
				timer.cancel();
				setOutputPin(id.shortValue(), timer.getState());
			}
		}
	}

	@Override
	public void connect() throws ValidationException, ImplementationException {
		try {
			this.rfDevice.openConnection(this.rfConsumer, CONNECTION_TIMEOUT);
			connectIo();
			this.connected = true;

			List<havis.device.rf.capabilities.Capabilities> rfCaps = this.rfDevice
					.getCapabilities(havis.device.rf.capabilities.CapabilityType.DEVICE_CAPABILITIES);

			if (rfCaps.size() > 0) {
				havis.device.rf.capabilities.DeviceCapabilities rfDevCaps = (havis.device.rf.capabilities.DeviceCapabilities) rfCaps.get(0);

				this.deviceCapabilities.setFirmware(rfDevCaps.getFirmware());
			}

			updatePinResetTimers();
		} catch (ConnectionException | havis.device.rf.exception.ImplementationException | havis.device.io.exception.ConnectionException
				| havis.device.io.exception.ImplementationException e) {
			throw new ImplementationException(e);
		}
	}

	@Override
	public void disconnect() throws ImplementationException {
		try {
			this.rfDevice.closeConnection();
			disconnectIo();
			this.connected = false;
		} catch (ConnectionException | havis.device.io.exception.ConnectionException | havis.device.io.exception.ImplementationException e) {
			throw new ImplementationException(e);
		}
	}

	private void connectIo() throws havis.device.io.exception.ConnectionException, havis.device.io.exception.ImplementationException {
		if (!ioConnected) {
			synchronized (syncIoConnectionState) {
				if (!ioConnected) {
					this.ioDevice.openConnection(this.ioConsumer, CONNECTION_TIMEOUT);
					ioConnected = true;
				}
			}
		}
	}

	private void disconnectIo() throws havis.device.io.exception.ConnectionException, havis.device.io.exception.ImplementationException {
		if (ioConnected && !hasActivePinResetTimers()) {
			synchronized (syncIoConnectionState) {
				if (ioConnected) {
					ioDevice.closeConnection();
					ioConnected = false;
				}
			}
		}
	}

	@Override
	public void defineTagOperation(long id, TagOperation operation) throws ValidationException, ImplementationException {
		synchronized (this.syncTagOperationList) {
			try {
				if (!this.tagOperationList.containsKey(id))
					this.tagOperationList.put(id, operation);
				else
					throw new ImplementationException("Tag operation with id '" + id + "' was already defined!");

			} catch (ImplementationException e) {
				throw e;
			} catch (Exception e) {
				throw new ImplementationException(e);
			}
		}
	}

	@Override
	public void undefineTagOperation(long id) throws ImplementationException {
		try {
			if (!this.tagOperationList.containsKey(id))
				throw new ImplementationException("Unkown tag operation ID '" + id + "!");

			if (this.tagObserverList.contains(id))
				this.disableTagOperation(id);

			synchronized (this.syncTagOperationList) {
				this.tagOperationList.remove(id);
			}
		} catch (ImplementationException e) {
			throw e;

		} catch (Exception e) {
			throw new ImplementationException(e);
		}
	}

	@Override
	public void enableTagOperation(long id) throws ImplementationException {
		try {
			synchronized (this.syncTagObserverList) {
				if (this.tagOperationList.containsKey(id)) {
					if (!this.tagObserverList.contains(id))
						this.tagObserverList.add(id);
				} else {
					throw new ImplementationException("Unkown reader operation ID '" + id + "!");
				}
				this.inventoryOperation = this.getInventoryOperation();
			}

			if (this.connected && this.tagObserverList.size() == 1) {
				this.startInventory();
			}
		} catch (ImplementationException e) {
			throw e;
		} catch (Exception e) {
			this.clientCallback.notify(new Message(Exits.Reader.Controller.Error, "Enable tag operation failed: " + e.getMessage(), e));
			throw new ImplementationException(e);
		}
	}

	protected RF_RInventoryOperation getInventoryOperation() {
		RF_RInventoryOperation inventoryOperation = new RF_RInventoryOperation();
		synchronized (this.syncTagObserverList) {

			if (Tag.isExtended())
				inventoryOperation.setTid(true);

			boolean[] dataBlocks = new boolean[0];

			for (Long id : this.tagObserverList) {
				synchronized (this.tagOperationList) {
					TagOperation operation = this.tagOperationList.get(id);
					if (operation != null) {
						if (operation.getOperations() != null && operation.getOperations().size() > 0) {
							for (havis.middleware.ale.base.operation.tag.Operation op : operation.getOperations()) {
								if (op.getField().getBank() == 0)
									inventoryOperation.setReserved(true);
								else if (op.getField().getBank() == 1)
									inventoryOperation.setEpc(true);
								else if (op.getField().getBank() == 2)
									inventoryOperation.setTid(true);
								else if (op.getField().getBank() == 3) {
									if (!inventoryOperation.isUser()) {
										inventoryOperation.setUser(UserReadMode.ON);
									}

									// Determine the required data blocks
									if (op.getField().getLength() > 0) {
										int ceiling = Calculator.size(op.getField().getLength() + op.getField().getOffset(), 16);
										if (dataBlocks.length < ceiling)
											dataBlocks = Arrays.copyOf(dataBlocks, ceiling);
										for (int i = op.getField().getOffset() / 16; i < ceiling; i++)
											dataBlocks[i] = true;
									} else {
										// force complete read
										inventoryOperation.setUser(UserReadMode.ON_COMPLETE);
									}
								}
							}
						}
					}
				}
			}
			inventoryOperation.setUserDataBlocks(dataBlocks);
		}
		return inventoryOperation;
	}

	@Override
	public void disableTagOperation(long id) throws ImplementationException {
		try {
			synchronized (this.syncTagObserverList) {
				if (this.tagObserverList.contains(id))
					this.tagObserverList.remove(id);
				else
					throw new ImplementationException("Reader operation ID '" + id + "' does not exist!");
				this.inventoryOperation = this.getInventoryOperation();
			}
			if (this.connected && this.tagObserverList.size() == 0)
				this.stopInventory();
		} catch (ImplementationException e) {
			throw e;
		} catch (Exception e) {
			throw new ImplementationException(e);
		}
	}

	private void startInventory() {
		inventoryLock.lock();
		try {
			this.doInventory = true;
			if (this.inventoryThread == null) {
				this.inventoryThread = new Thread(new Runnable() {
					@Override
					public void run() {
						try {
							inventory();
						} catch (InterruptedException e) {
						} catch (Exception e) {
							log.log(Level.SEVERE, "Failed to run inventory: "
									+ e.toString(), e);
						}
					}
				}, RF_R300ReaderConnector.class.getSimpleName() + " "
						+ this.deviceCapabilities.getName() + " inventory()");
				this.inventoryThread.start();
			} else {
				inventoryCondition.signal();
			}
		} finally {
			inventoryLock.unlock();
		}
	}

	private void stopInventory() {
		this.inventoryLock.lock();
		try {
			this.doInventory = false;
		} finally {
			this.inventoryLock.unlock();
		}
	}

	private boolean isInventoryEnabled() {
		while (!this.doInventory) {
			this.inventoryLock.lock();
			try {
				try {
					this.inventoryCondition.await();
				} catch (InterruptedException e) {
					return false;
				}
			} finally {
				this.inventoryLock.unlock();
			}
		}
		return true;
	}

	private void inventory() throws Exception {
		while (isInventoryEnabled()) {
			try {
				synchronized (this.syncReader) {


					List<Filter> filters = new ArrayList<>();
					Map<String, havis.device.rf.tag.operation.TagOperation> operations = new LinkedHashMap<>();

					int resultCount = 4; // epc, tid, pwd, user

					RF_RInventoryOperation inventoryOperation;
					Map<Long, TagOperation> tagOperations = new HashMap<>();

					// create snapshot of current operations
					synchronized (this.syncTagObserverList) {
						inventoryOperation = this.inventoryOperation.clone();
						synchronized (this.syncTagOperationList) {
							for (Long id : this.tagObserverList) {
								TagOperation op = tagOperationList.get(id);
								if (op != null) {
									tagOperations.put(id, op);
								}
							}
						}
					}

					if (inventoryOperation.isEpc())
						operations.put("inv-rd-epc", new havis.device.rf.tag.operation.ReadOperation() {
							{
								setBank(RFUtils.BANK_EPC);
								setLength((short) 0);
								setOffset((short) 0);
								setOperationId("inv-rd-epc");
							}
						});

					if (inventoryOperation.isTid() || Tag.isExtended())
						operations.put("inv-rd-tid", new havis.device.rf.tag.operation.ReadOperation() {
							{
								setBank(RFUtils.BANK_TID);
								setLength((short) 0);
								setOffset((short) 0);
								setOperationId("inv-rd-tid");
							}
						});

					if (inventoryOperation.isReserved())
						operations.put("inv-rd-psw", new havis.device.rf.tag.operation.ReadOperation() {
							{
								setBank(RFUtils.BANK_PSW);
								setLength((short) 0);
								setOffset((short) 0);
								setOperationId("inv-rd-psw");
							}
						});

					if (inventoryOperation.isUser()) {

						List<ReadOperation> rdOps = getUserbankReadOperations(inventoryOperation);

						for (ReadOperation rdOp : rdOps)
							operations.put(rdOp.getOperationId(), rdOp);

						/*
						 * extend the result count depending on the number of
						 * read operations
						 */
						// resultCount = resultCount + rdOps.size() - 1;
					}

					List<TagData> inventoryTagList = this.rfDevice.execute(antennas, filters, new ArrayList<>(operations.values()));
					if (inventoryTagList.size() > 0) {
						for (TagData tag : inventoryTagList) {
							Tag reportTag = new Tag(tag.getEpc());
							reportTag.setPc(RFUtils.shortToBytes(tag.getPc()));

							reportTag.setSighting(new Sighting(this.deviceCapabilities.getName().toString(), tag.getAntennaID(), tag.getRssi(), reportTag.getFirstTime()));

							boolean readSuccess = true;

							havis.middleware.ale.base.operation.tag.result.ReadResult[] readResult = new havis.middleware.ale.base.operation.tag.result.ReadResult[resultCount];

							for (OperationResult opRes : tag.getResultList()) {
								ReadResult rdRes = (ReadResult) opRes;

								switch (rdRes.getOperationId()) {

								case "inv-rd-psw":
									if (!readSuccess) {
										readResult[0] = TypeConverter.convert(ResultState.MISC_ERROR_TOTAL, new byte[0]);
										break;
									}
									readResult[0] = TypeConverter.convert(rdRes, null, (ReadOperation) operations.get(rdRes.getOperationId()));
									readSuccess = readResult[0].getState() == ResultState.SUCCESS;
									break;

								case "inv-rd-epc":
									if (!readSuccess) {
										readResult[1] = TypeConverter.convert(ResultState.MISC_ERROR_TOTAL, new byte[0]);
										break;
									}
									readResult[1] = TypeConverter.convert(rdRes, null, (ReadOperation) operations.get(rdRes.getOperationId()));
									readSuccess = readResult[1].getState() == ResultState.SUCCESS;
									break;

								case "inv-rd-tid":
									if (!readSuccess) {
										readResult[2] = TypeConverter.convert(ResultState.MISC_ERROR_TOTAL, new byte[0]);
										break;
									}
									readResult[2] = TypeConverter.convert(rdRes, null, (ReadOperation) operations.get(rdRes.getOperationId()));
									readSuccess = readResult[2].getState() == ResultState.SUCCESS;
									if (Tag.isExtended())
										reportTag.setTid(readResult[2].getData() != null ? readResult[2].getData() : new byte[0]);
									break;

								default: // user bank
									if (!readSuccess) {
										readResult[3] = TypeConverter.convert(ResultState.MISC_ERROR_TOTAL, new byte[0]);
										break;
									}

									readResult[3] = TypeConverter.convert(rdRes, readResult[3], (ReadOperation) operations.get(rdRes.getOperationId()));
									readSuccess = readResult[3].getState() == ResultState.SUCCESS;
									break;
								}
							}

							sendInventoryReport(new InventoryReport(reportTag, readResult), tagOperations);
						}
						if (!this.readerErrorOccurred && this.readerErrorCount > 0)
							this.readerErrorCount--;
					}
					this.executeOperation();

				}
				notifyConnectionErrorResolved();
			} catch (Exception e) {

				this.notifyConnectionError("Exception occurred during Inventory: " + e.getMessage());

				this.clientCallback.notify(new Message(Exits.Reader.Controller.Error,
						"Exception occurred during Inventory: " + e.getMessage() + "(" + this.deviceCapabilities.getName() + ")", e));
				throw e;
			}
			Thread.yield(); // to enable other threads to process
		}
	}

	private List<havis.device.rf.tag.operation.ReadOperation> getUserbankReadOperations(RF_RInventoryOperation inventoryOperation) {
		List<havis.device.rf.tag.operation.ReadOperation> res = new ArrayList<>();

		boolean allDataBlocksFalse = true;

		// the offset for each generated read operation
		int offset = 0;

		// the length (i.e. number of words) for each generated read operation
		int words = 0;

		// operation counter to generate unique operation IDs
		int opCnt = 0;

		boolean[] userDataBlocks = inventoryOperation.getUserDataBlocks();

		for (int i = 0; userDataBlocks != null && i < userDataBlocks.length; i++) {

			// if current dataBlock is false continue
			if (!userDataBlocks[i])
				continue;

			// else (i.e. current dataBlock is true)
			else {
				// disable flag 'allDataBlocksFalse'
				allDataBlocksFalse = false;

				// if current data block is not the first one and the
				// previous data block was false set the offset to the
				// address i of this data block and set length back to 0

				if (i > 0 && !userDataBlocks[i - 1]) {
					offset = i;
					words = 0;
				}

				// if current data block is true increment the length to be read
				if (userDataBlocks[i])
					words++;

				// if current is last one or next is false, add new readOp to
				// result
				if (i == userDataBlocks.length - 1 || !userDataBlocks[i + 1]) {
					ReadOperation rdOp = new ReadOperation();
					rdOp.setBank(RFUtils.BANK_USR);
					rdOp.setLength((short) words);
					rdOp.setOffset((short) offset);
					rdOp.setOperationId("inv-rd-usr-" + (opCnt++));
					res.add(rdOp);
				}
				// (else continue)
			}
		}

		// the following line enables the optimization that generates a read
		// operation for
		// the complete user bank only, if all data blocks are false or the data
		// blocks array
		// is null or empty or a complete read is required by an operation.
		// Without this
		// optimization an operation to read the complete bank is generated
		// anyway
		// (which is required to provide the behavior expected by the
		// middleware)
		if (allDataBlocksFalse || inventoryOperation.isForceUserComplete()) {
			ReadOperation rdOp = new havis.device.rf.tag.operation.ReadOperation();
			rdOp.setBank(RFUtils.BANK_USR);
			rdOp.setLength((short) 0);
			rdOp.setOffset((short) 0);
			rdOp.setOperationId("inv-rd-usr-" + opCnt);
			res.add(0, rdOp); // always add to the start
		}

		return res;
	}

	protected boolean executeOperation() throws ImplementationException {
		boolean executed = false;
		this.syncExecuteTagOperation.lock();
		try {
			if (this.executeTagOperation.getValue() != null) {
				if (this.executeAbort) {
					this.executeTagOperation = new KeyValuePair<>();
					if (this.connected && this.tagObserverList.size() == 0)
						this.stopInventory();

					if (executeEvent.availablePermits() == 0)
						executeEvent.release();
					return false;
				}

				List<havis.device.rf.tag.Filter> filters = TypeConverter.generateFilters(this.executeTagOperation.getValue());
				List<havis.device.rf.tag.operation.TagOperation> operations = TypeConverter.convert(this.executeTagOperation.getValue());

				// move IDs of psw-ops to special list and remove them from
				// the operations list
				List<Integer> pswOpIds = new ArrayList<>();
				Iterator<havis.device.rf.tag.operation.TagOperation> itOps = operations.iterator();
				while (itOps.hasNext()) {
					havis.device.rf.tag.operation.TagOperation tagOp = itOps.next();
					if (tagOp.getOperationId().startsWith("psw-")) {
						pswOpIds.add(Integer.parseInt(tagOp.getOperationId().substring(4)));
						itOps.remove();
					}
				}
				if (Tag.isExtended())
					operations.add(0, new ReadOperation("rd-tid", RFUtils.BANK_TID, (short)0, (short)0, 0));
				List<TagData> results = this.rfDevice.execute(antennas, filters, operations);
				
				for (TagData tag : results) {
					Tag reportTag = new Tag(tag.getEpc());
					reportTag.setPc(RFUtils.shortToBytes(tag.getPc()));

					reportTag.setSighting(new Sighting("BuiltIn", tag.getAntennaID(), tag.getRssi(), reportTag.getFirstTime()));

					if (Tag.isExtended())
						reportTag.setTid(((ReadResult)tag.getResultList().remove(0)).getReadData());

					Map<Integer, havis.middleware.ale.base.operation.tag.result.Result> execResultMap = new HashMap<>();

					// add results for psw operation IDs
					for (Integer pswOpId : pswOpIds) {
						execResultMap.put(pswOpId, new havis.middleware.ale.base.operation.tag.result.PasswordResult(ResultState.SUCCESS));
					}

					// iterate through list of tag data results and generate
					// entries for execResultMap
					for (OperationResult opRes : tag.getResultList()) {							
						havis.middleware.ale.base.operation.tag.result.Result res = TypeConverter.convert(opRes);
						execResultMap.put(Integer.parseInt(opRes.getOperationId()), res);
					}

					reportTag.setResult(execResultMap);
					this.sendExecuteReport(reportTag);
					executed = true;
					break;
				}

				if (!executed) {
					this.sendExecuteErrorReport(new HashMap<Integer, havis.middleware.ale.base.operation.tag.result.Result>());
				}
			}
			notifyConnectionErrorResolved();

		} catch (Exception e) {
			notifyConnectionError(e.getMessage());
			throw new ImplementationException(e);
		} finally {
			this.syncExecuteTagOperation.unlock();
		}
		return executed;
	}

	private void sendInventoryReport(InventoryReport report, Map<Long, TagOperation> operations) throws InterruptedException {
		for (Entry<Long, TagOperation> entry : operations.entrySet()) {
			Map<Integer, havis.middleware.ale.base.operation.tag.result.Result> opResultList;

			if (entry.getValue().getOperations() != null) {
				opResultList = ReaderUtils.toResult(report.readResult, entry.getValue().getOperations());
			} else {
				opResultList = new HashMap<Integer, havis.middleware.ale.base.operation.tag.result.Result>();
			}

			Tag tag = report.tag.clone();
			tag.setResult(opResultList);
			this.clientCallback.notify(entry.getKey().longValue(), tag);
		}
	}

	private void sendExecuteReport(Tag executeTag) throws InterruptedException {
		this.syncExecuteTagOperation.lock();
		try {
			this.clientCallback.notify(this.executeTagOperation.getKey(), executeTag);
			this.executeTagOperation = new KeyValuePair<>();
			if (this.connected && this.tagObserverList.size() == 0)
				this.stopInventory();

			if (executeEvent.availablePermits() == 0)
				executeEvent.release();

		} catch (Exception e) {
			this.clientCallback.notify(new Message(Exits.Reader.Controller.Error, "Sending execute report failed: " + e.getMessage(), e));
		} finally {
			this.syncExecuteTagOperation.unlock();
		}
	}

	private void sendExecuteErrorReport(Map<Integer, havis.middleware.ale.base.operation.tag.result.Result> resultList) throws InterruptedException {
		this.syncExecuteTagOperation.lock();
		try {
			for (havis.middleware.ale.base.operation.tag.Operation op : this.executeTagOperation.getValue().getOperations()) {
				if (resultList.containsKey(op.getId()))
					continue;

				switch (op.getType()) {
				case KILL:
					resultList.put(op.getId(), new havis.middleware.ale.base.operation.tag.result.KillResult(
							havis.middleware.ale.base.operation.tag.result.ResultState.MISC_ERROR_TOTAL));
					break;
				case LOCK:
					resultList.put(op.getId(), new havis.middleware.ale.base.operation.tag.result.LockResult(
							havis.middleware.ale.base.operation.tag.result.ResultState.MISC_ERROR_TOTAL));
					break;
				case PASSWORD:
					resultList.put(op.getId(), new havis.middleware.ale.base.operation.tag.result.PasswordResult(
							havis.middleware.ale.base.operation.tag.result.ResultState.MISC_ERROR_TOTAL));
					break;
				case READ:
					resultList.put(op.getId(), new havis.middleware.ale.base.operation.tag.result.ReadResult(
							havis.middleware.ale.base.operation.tag.result.ResultState.MISC_ERROR_TOTAL, new byte[0]));
					break;
				case WRITE:
					resultList.put(op.getId(), new havis.middleware.ale.base.operation.tag.result.WriteResult(
							havis.middleware.ale.base.operation.tag.result.ResultState.MISC_ERROR_TOTAL, 0));
					break;
				case CUSTOM:
					resultList.put(op.getId(), new havis.middleware.ale.base.operation.tag.result.CustomResult(
							havis.middleware.ale.base.operation.tag.result.ResultState.MISC_ERROR_TOTAL, new byte[0]));
					break;
				default:
					break;
				}
			}
			Tag tag = new Tag((byte[]) null);
			tag.setResult(resultList);

			this.clientCallback.notify(this.executeTagOperation.getKey(), tag);
			this.executeTagOperation = new KeyValuePair<>();

			if (this.connected && this.tagObserverList.size() == 0)
				this.stopInventory();

			if (executeEvent.availablePermits() == 0)
				executeEvent.release();

		} catch (Exception e) {
			this.clientCallback.notify(new Message(Exits.Reader.Controller.Error, "Sending execute error report failed: " + e.getMessage(), e));
		} finally {
			this.syncExecuteTagOperation.unlock();
		}
	}

	@Override
	public void executeTagOperation(long id, TagOperation operation) throws ValidationException, ImplementationException {
		if (!this.connected)
			throw new ValidationException("ReaderConnector was not connected to (" + rfDevice.toString() + ")!");

		try {
			executeEvent.acquire();
			boolean validExecute = true;

			this.syncExecuteTagOperation.lock();
			try {
				this.executeAbort = false;

				this.executeTagOperation = new KeyValuePair<Long, TagOperation>(id, operation);

				Map<Integer, havis.middleware.ale.base.operation.tag.result.Result> errorList = this.validateExecuteOperation(operation);
				if (errorList.size() > 0) {
					validExecute = false;
					this.sendExecuteErrorReport(errorList);
				}
			} finally {
				this.syncExecuteTagOperation.unlock();
			}

			if (validExecute && this.tagObserverList.size() == 0)
				this.startInventory();
			
		} catch (Exception e) {
			e.printStackTrace();
			throw new ImplementationException(e.getMessage() + " (" + this.deviceCapabilities.getName() + ")!");
		}
	}
	
	protected Map<Integer, havis.middleware.ale.base.operation.tag.result.Result> validateExecuteOperation(TagOperation operation) {
		Map<Integer, havis.middleware.ale.base.operation.tag.result.Result> errorList = new Hashtable<>();
		for (havis.middleware.ale.base.operation.tag.Operation op : operation.getOperations()) {
			switch (op.getType()) {
				case KILL:
					if (op.getData().length > 4)
						errorList.put(op.getId(), new havis.middleware.ale.base.operation.tag.result.KillResult(
								havis.middleware.ale.base.operation.tag.result.ResultState.OP_NOT_POSSIBLE_ERROR));
					break;
				case LOCK:	
					LockType lockType = LockType.values()[op.getData()[0]];
					switch (lockType) {
						case LOCK:							
						case PERMALOCK:							
						case PERMAUNLOCK:							
						case UNLOCK:
							break;
						default:
							errorList.put(op.getId(), new havis.middleware.ale.base.operation.tag.result.LockResult(
								havis.middleware.ale.base.operation.tag.result.ResultState.OP_NOT_POSSIBLE_ERROR));
						break;
					}
					switch (op.getField().getBank()) {
						case 0:
							if (!(op.getField().getOffset() == 0 && op.getField().getLength() == 32)
									&& !(op.getField().getOffset() == 32 && op.getField().getLength() == 32))
								errorList.put(op.getId(), new havis.middleware.ale.base.operation.tag.result.LockResult(
										havis.middleware.ale.base.operation.tag.result.ResultState.OP_NOT_POSSIBLE_ERROR));
							break;
						case 1:
							if (!(op.getField().getOffset() == 0 && op.getField().getLength() == 0))
								errorList.put(op.getId(), new havis.middleware.ale.base.operation.tag.result.LockResult(
										havis.middleware.ale.base.operation.tag.result.ResultState.OP_NOT_POSSIBLE_ERROR));
							break;
						case 2:
							if (!(op.getField().getOffset() == 0 && op.getField().getLength() == 0))
								errorList.put(op.getId(), new havis.middleware.ale.base.operation.tag.result.LockResult(
										havis.middleware.ale.base.operation.tag.result.ResultState.OP_NOT_POSSIBLE_ERROR));
							break;
						case 3:
							if (!(op.getField().getOffset() == 0 && op.getField().getLength() == 0))
								errorList.put(op.getId(), new havis.middleware.ale.base.operation.tag.result.LockResult(
										havis.middleware.ale.base.operation.tag.result.ResultState.OP_NOT_POSSIBLE_ERROR));
							break;
						default:
							errorList.put(op.getId(), new havis.middleware.ale.base.operation.tag.result.LockResult(
									havis.middleware.ale.base.operation.tag.result.ResultState.OP_NOT_POSSIBLE_ERROR));
							break;
					}
					break;
				case PASSWORD:
					if (op.getData().length > 4)
						errorList.put(op.getId(), new havis.middleware.ale.base.operation.tag.result.PasswordResult(
								havis.middleware.ale.base.operation.tag.result.ResultState.OP_NOT_POSSIBLE_ERROR));
					break;				
				case WRITE:
					if (op.getData().length % 2 != 0 || op.getField().getOffset() % 16 != 0 || op.getField().getLength() % 16 != 0
							|| ((op.getField().getLength() != 0) && (op.getData().length / 2 != op.getField().getLength() / 16))) {
						errorList.put(op.getId(), new havis.middleware.ale.base.operation.tag.result.WriteResult(
								havis.middleware.ale.base.operation.tag.result.ResultState.OP_NOT_POSSIBLE_ERROR, 0));
					}
					break;
				case CUSTOM:
				case READ:
				default:
					break;
			}
		}
		return errorList;
	}

	/**
	 * Method to abort a running reader operation.
	 * 
	 * @param id
	 *            The unique id of the reader operation.
	 * @throws ImplementationException
	 */
	@Override
	public void abortTagOperation(long id) throws ImplementationException {
		this.syncExecuteTagOperation.lock();
		try {
			this.executeAbort = true;
		} finally {
			this.syncExecuteTagOperation.unlock();
		}
	}

	/**
	 * Method to define new port observation on the reader.
	 * 
	 * @param id
	 *            The unique id to identify the port observation
	 * @param oberservation
	 *            The observation
	 * @throws ValidationException
	 * @throws ImplementationException
	 */
	@Override
	public void definePortObservation(long id, PortObservation observation) throws ValidationException, ImplementationException {
		try {
			if (this.connected && this.portObserverList.size() == 0)
				this.changeInputEnabledState(true);

			this.validateObservation(observation);

			synchronized (this.syncPortObservationList) {
				if (!this.portObservationList.containsKey(id))
					this.portObservationList.put(id, observation);
				else
					throw new ImplementationException("Port observation id with '" + id + "' was already defined for " + this.ioDevice.toString());
			}
		} catch (ImplementationException e) {
			throw e;
		} catch (Exception e) {
			throw new ImplementationException(e.getMessage() + " (" + this.ioDevice.toString() + ")!");
		}
	}

	private void validateObservation(PortObservation observation) {

		/* Hash map that allows to retrieve port configs by ID */
		Map<Integer, IOConfiguration> ioConfigMap = new HashMap<>();

		List<Configuration> configs = null;
		try {
			/* read all IO configs from the IO controller */
			configs = this.ioDevice.getConfiguration(havis.device.io.Type.IO, (short) 0);
		} /* throw an implementation exception if that fails */
		catch (Exception e) {
			this.clientCallback.notify(new Message(Exits.Reader.Controller.Error, "Getting IO configuration failed: " + e.getMessage(), e));
			return;
		}

		/* store all IO configs in the hash map */
		for (Configuration conf : configs) {
			IOConfiguration ioc = (IOConfiguration) conf;
			ioConfigMap.put(new Integer(ioc.getId()), ioc);
		}

		for (Pin pin : observation.getPins()) {
			if (pin.getType() != Type.INPUT)
				this.clientCallback.notify(new Message(Exits.Reader.Controller.Warning, "Only input pins could be observed from " + ioDevice + "! "));

			if (ioConfigMap.get(pin.getId()) == null || ioConfigMap.get(pin.getId()).getDirection() != Direction.INPUT)
				this.clientCallback.notify(new Message(Exits.Reader.Controller.Warning,
						"Pin with ID " + pin.getId() + " is no input pin and cannot be obversed with " + this.ioDevice + "! "));
		}
	}

	/**
	 * Method to undefine a port observation on the reader.
	 * 
	 * @param id
	 *            The unique id to identify the port observation
	 * @throws ImplementationException
	 */
	@Override
	public void undefinePortObservation(long id) throws ImplementationException {
		try {
			if (!this.portObservationList.containsKey(id))
				throw new ImplementationException("Unkown port observation ID '" + id + "' for " + this.ioDevice + "!");

			if (this.portObserverList.contains(id))
				this.disablePortObservation(id);

			synchronized (syncPortObservationList) {
				this.portObservationList.remove(id);

				if (portObservationList.size() == 0)
					this.changeInputEnabledState(false);
			}
		} catch (ImplementationException e) {
			throw e;
		} catch (Exception e) {
			throw new ImplementationException(e.getMessage() + " (" + this.ioDevice.toString() + ")!");
		}
	}

	private void changeInputEnabledState(boolean enabled)
			throws havis.device.io.exception.ConnectionException, ParameterException, havis.device.io.exception.ImplementationException {

		synchronized (this.syncReader) {
			List<Configuration> configs = this.ioDevice.getConfiguration(havis.device.io.Type.IO, (short) 0);

			for (Configuration conf : configs) {
				IOConfiguration ioc = (IOConfiguration) conf;
				if (ioc.getDirection() == Direction.INPUT)
					ioc.setEnable(enabled);
			}

			this.ioDevice.setConfiguration(configs);
		}

	}

	/**
	 * Method to enable a port observation on the reader. After this method the
	 * reader connector will report result using the given callback method.
	 * 
	 * @param id
	 *            The unique id to identify the port observation
	 * @throws ImplementationException
	 */
	@Override
	public void enablePortObservation(long id) throws ImplementationException {
		try {
			synchronized (syncPortObserverList) {
				if (this.portObservationList.containsKey(id))
					this.portObserverList.add(id);
				else
					throw new ImplementationException("Unkown port observation ID '" + id + "' for " + this.ioDevice + "!");

				if (this.connected && this.portObserverList.size() == 1)
					this.doObservation = true;
			}
		} catch (ImplementationException e) {
			throw e;
		} catch (Exception e) {
			throw new ImplementationException(e.getMessage() + " (" + this.ioDevice + ")!");
		}
	}

	/**
	 * Method to disable a port observation on the reader. After this method the
	 * reader connector will no longer report results.
	 * 
	 * @param id
	 *            The unique id to identify the port observation
	 * @throws ImplementationException
	 */
	@Override
	public void disablePortObservation(long id) throws ImplementationException {
		try {
			synchronized (this.syncPortObserverList) {
				if (this.portObserverList.contains(id))
					this.portObserverList.remove(id);
				else
					throw new ImplementationException("Port observation ID '" + id + "' was not active for " + this.ioDevice + "!");

				if (this.connected && this.portObserverList.size() == 0)
					this.doObservation = false;
			}
		} catch (ImplementationException e) {
			throw e;
		} catch (Exception e) {
			throw new ImplementationException(e.getMessage() + " (" + this.ioDevice.toString() + ")!");
		}
	}

	private void setOutputPin(short id, havis.device.io.State state) {
		IOConfiguration ioc = null;
		List<Configuration> c;
		try {
			c = this.ioDevice.getConfiguration(havis.device.io.Type.IO, id);
			if (c.size() > 0)
				ioc = (IOConfiguration) c.get(0);

			if (ioc != null && ioc.getDirection() == Direction.OUTPUT && ioc.getState() != state) {
				ioc.setState(state);

				/* write the port instance to a new config list */
				List<Configuration> newConf = Arrays.asList(new Configuration[] { ioc });

				try {
					/* send the changed config to the IO controller */
					ioDevice.setConfiguration(newConf);
				} catch (Exception e) {
					log.log(Level.SEVERE, "Failed to set output pin.", e);
				}
			}
		} catch (Exception ec) {
			log.log(Level.SEVERE, "Failed to get IO configuration.", ec);
		} finally {
			if (!connected) {
				try {
					disconnectIo();
				} catch (havis.device.io.exception.ConnectionException | havis.device.io.exception.ImplementationException e) {
					log.log(Level.SEVERE, "Failed to disconnect IO after setting output.", e);
				}
			}
		}
	}

	@Override
	public void executePortOperation(long id, PortOperation operation) throws ValidationException, ImplementationException {

		synchronized (syncReader) {

			/* throw an exception if no connection to reader is established */
			if (!connected) {
				notifyConnectionError("ReaderConnector was not connected to IO device (" + this.deviceCapabilities.getName() + ")");
				throw new ValidationException("ReaderConnector was not connected to IO device (" + this.deviceCapabilities.getName() + ")");
			}

			/* hash map to store the operation results */
			Map<Integer, havis.middleware.ale.base.operation.port.result.Result> result = new Hashtable<>();

			/* flags to signal if an operation has failed */
			havis.middleware.ale.base.operation.port.result.Result.State errorState = null;
			boolean hasPreviousError = false;

			/* iterate through all operations */
			for (Operation o : operation.getOperations()) {

				List<Configuration> configs = null;
				IOConfiguration ioc = null;
				try {
					/* read all IO configs from the IO controller */
					configs = this.ioDevice.getConfiguration(havis.device.io.Type.IO, (short) o.getPin().getId());
					if (configs.size() > 0)
						ioc = (IOConfiguration) configs.get(0);
				} /* throw an implementation exception if that fails */
				catch (ParameterException e) {
					errorState = havis.middleware.ale.base.operation.port.result.Result.State.PORT_NOT_FOUND_ERROR;
				} catch (Exception e) {
					errorState = havis.middleware.ale.base.operation.port.result.Result.State.MISC_ERROR_TOTAL;
				}

				switch (o.getType()) {

				/* in case of a read operation */
				case READ:
					/* if an error has occurred before */
					if (errorState != null) {
						/*
						 * generate a misc error total result and store it in
						 * the result map
						 */
						result.put(o.getId(), new havis.middleware.ale.base.operation.port.result.ReadResult(
								hasPreviousError ? havis.middleware.ale.base.operation.port.result.Result.State.MISC_ERROR_TOTAL : errorState));

						/* if no error has occurred so far */
					} else {

						/*
						 * check if port exists and its direction corresponds to
						 * the one specified in the operation
						 */
						if (ioc != null && ((o.getPin().getType() == Type.INPUT && ioc.getDirection() == Direction.INPUT)
								|| (o.getPin().getType() == Type.OUTPUT && ioc.getDirection() == Direction.OUTPUT))) {

							/* generate a read result from the port state */
							result.put(o.getId(), new havis.middleware.ale.base.operation.port.result.ReadResult(State.SUCCESS,
									(byte) (ioc.getState() == havis.device.io.State.HIGH ? 0x01 : 0x00)));
						}
						/*
						 * if port does not exist or direction differs from the
						 * one specified in the operation, generate a port not
						 * found error and store it in the result
						 */
						else {
							result.put(o.getId(), new havis.middleware.ale.base.operation.port.result.ReadResult(
									errorState = havis.middleware.ale.base.operation.port.result.Result.State.PORT_NOT_FOUND_ERROR));
						}
					}
					break;

				/* in case of a write operation */
				case WRITE:
					/* if an error has occurred before */
					if (errorState != null) {
						/*
						 * generate a error result and store it in the result
						 * map
						 */
						result.put(o.getId(), new havis.middleware.ale.base.operation.port.result.WriteResult(
								hasPreviousError ? havis.middleware.ale.base.operation.port.result.Result.State.MISC_ERROR_TOTAL : errorState));
					} else {

						/*
						 * check if the pin in the operation is specified as
						 * output and no duration has been specified
						 */
						if (o.getPin().getType() == Type.OUTPUT) {
							/*
							 * check if port exists and its direction
							 * corresponds to the one specified in the operation
							 */
							if (ioc != null && ioc.getDirection() == Direction.OUTPUT) {

								/* get the IO config object from the hash map */
								// IOConfiguration ioc =
								// ioConfigMap.get(o.getPin().getId());

								/*
								 * generate the target state as specified in the
								 * operation's data
								 */
								havis.device.io.State targetState = o.getData() == 0x01 ? havis.device.io.State.HIGH : havis.device.io.State.LOW;

								/*
								 * check if the port does not already have the
								 * desired state or if this is a call with
								 * duration
								 */
								if (ioc.getState() != targetState || (o.getDuration() != null && o.getDuration().longValue() > 0)) {

									havis.device.io.State oldState = ioc.getState();

									/*
									 * change the state of the port instance
									 * from the hash map
									 */
									ioc.setState(targetState);

									/*
									 * write the port instance to a new config
									 * list
									 */
									List<Configuration> newConf = Arrays.asList(new Configuration[] { ioc });

									try {
										/*
										 * send the changed config to the IO
										 * controller
										 */
										ioDevice.setConfiguration(newConf);

										/*
										 * generate a success result for this
										 * write operation and store it in the
										 * hash map
										 */
										result.put(o.getId(), new havis.middleware.ale.base.operation.port.result.WriteResult(State.SUCCESS));

										handleDuration(o, oldState);
									}
									/*
									 * generate a misc error total error if that
									 * fails
									 */
									catch (Exception e) {
										result.put(o.getId(), new havis.middleware.ale.base.operation.port.result.WriteResult(
												errorState = havis.middleware.ale.base.operation.port.result.Result.State.MISC_ERROR_TOTAL));
									}
								} else
									result.put(o.getId(), new havis.middleware.ale.base.operation.port.result.WriteResult(State.SUCCESS));
							}
							/*
							 * if port does not exist or its direction differs
							 * from the one specified in the operation, generate
							 * a port not found error
							 */
							else {
								result.put(o.getId(), new havis.middleware.ale.base.operation.port.result.WriteResult(
										errorState = havis.middleware.ale.base.operation.port.result.Result.State.PORT_NOT_FOUND_ERROR));
							}
						}
						/*
						 * if the pin in the operation is not specified as
						 * output or a duration was specified (not supported),
						 * generate an op not possible error
						 */
						else {
							result.put(o.getId(), new havis.middleware.ale.base.operation.port.result.WriteResult(
									errorState = havis.middleware.ale.base.operation.port.result.Result.State.OP_NOT_POSSIBLE_ERROR));
						}
					}
					break;
				}

				/*
				 * set hasPreviousError to true if errorState is set to force
				 * every following result to have the status MISC_ERROR_TOTAL
				 */
				hasPreviousError = errorState != null;

			}

			if (this.clientCallback != null)
				this.clientCallback.notify(id, new Port(result));

			notifyConnectionErrorResolved();
		}

	}

	private void handleDuration(Operation operation, havis.device.io.State oldState) throws ImplementationException {
		if (oldState == null) {
			ImplementationException e = new ImplementationException("Failed to retrieve old pin state for duration");
			log.log(Level.SEVERE, e.getReason(), e);
			throw e;
		}

		final Short pinId = Short.valueOf((short) operation.getPin().getId());
		StateResetTimer pinResetTimer = this.pinResetTimers.get(pinId);

		if (operation.getDuration() != null && operation.getDuration().longValue() > 0) {
			if (pinResetTimer == null) {
				// currently no time set, schedule
				final StateResetTimer timer = new StateResetTimer(oldState);
				this.pinResetTimers.put(pinId, timer);
				timer.schedule(new TimerTask() {
					@Override
					public void run() {
						synchronized (syncReader) {
							timer.cancel();
							pinResetTimers.put(pinId, null);
							setOutputPin(pinId.shortValue(), timer.getState());
						}
					}
				}, operation.getDuration().longValue());
			} else {
				// cancel existing timer and reschedule with same old state
				pinResetTimer.cancel();
				final StateResetTimer timer = new StateResetTimer(pinResetTimer.getState());
				this.pinResetTimers.put(pinId, timer);
				timer.schedule(new TimerTask() {
					@Override
					public void run() {
						synchronized (syncReader) {
							timer.cancel();
							pinResetTimers.put(pinId, null);
							setOutputPin(pinId.shortValue(), timer.getState());
						}
					}
				}, operation.getDuration().longValue());
			}
		} else if (pinResetTimer != null) {
			// no duration, cancel timer for this pin
			pinResetTimer.cancel();
			this.pinResetTimers.put(pinId, null);
		}
	}

	/**
	 * Method to send an observation report to callback
	 * 
	 * @param port
	 */
	protected void sendObservationReport(Port port) {
		try {
			synchronized (syncPortObserverList) {
				for (Long id : portObserverList) {
					clientCallback.notify(id, port);
				}
			}
		} catch (Exception e) {
			clientCallback.notify(new Message(Exits.Reader.Controller.Error, "Sending observation report failed: " + e.getMessage(), e));
		}
	}

	@Override
	public RCConfig getConfig() throws ImplementationException {
		return null;
	}

	@Override
	public void dispose() throws ImplementationException {
		// a single instance of this class is used during the lifetime of the
		// VM, so this method is only used to disconnect the connection and
		// reset all states
		flushPinResetTimers();
		stopInventory();
		this.inventoryLock.lock();
		try {
			if (this.inventoryThread != null) {
				this.inventoryThread.interrupt();
				this.inventoryThread = null;
			}
		} finally {
			this.inventoryLock.unlock();
		}
		if (this.connected) {
			this.disconnect();
		}
	}

	@Override
	public List<Capabilities> getCapabilities(CapabilityType capType) {
		List<Capabilities> ret = new ArrayList<>();

		if (capType == CapabilityType.ALL || capType == CapabilityType.ALL)
			ret.add(new DeviceCapabilities(this.deviceCapabilities.getName(), this.deviceCapabilities.getManufacturer(), this.deviceCapabilities.getModel(),
					this.deviceCapabilities.getFirmware()));

		return ret;
	}

	@Override
	public List<havis.util.monitor.Configuration> getConfiguration(ConfigurationType configType, short antennaId) {

		if (!connected)
			return null;

		List<havis.util.monitor.Configuration> ret = new ArrayList<>();

		if (configType == ConfigurationType.ALL || configType == ConfigurationType.ANTENNA_CONFIGURATION) {
			try {
				List<havis.device.rf.configuration.Configuration> rfAntConfigs = rfDevice
						.getConfiguration(havis.device.rf.configuration.ConfigurationType.ANTENNA_CONFIGURATION, antennaId, (short) 0, (short) 0);

				for (havis.device.rf.configuration.Configuration rfCfg : rfAntConfigs) {
					havis.device.rf.configuration.AntennaConfiguration rfAntCfg = (havis.device.rf.configuration.AntennaConfiguration) rfCfg;

					AntennaConfiguration antCfg = new AntennaConfiguration();
					antCfg.setId(rfAntCfg.getId());

					switch (rfAntCfg.getConnect()) {
					case AUTO:
						antCfg.setConnect(havis.util.monitor.ConnectType.AUTO);
						break;
					case FALSE:
						antCfg.setConnect(havis.util.monitor.ConnectType.FALSE);
						break;
					case TRUE:
						antCfg.setConnect(havis.util.monitor.ConnectType.TRUE);
						break;
					}

					ret.add(antCfg);
				}

			} catch (havis.device.rf.exception.ConnectionException | havis.device.rf.exception.ImplementationException e) {
				log.log(Level.SEVERE, "Failed to get antenna configuration.", e);
				clientCallback.notify(new Message(Exits.Reader.Controller.Error, "Failed to get antenna configuration: " + e.getMessage(), e));
				return null;
			}
		}

		return ret;
	}

	@Override
	public void setConfiguration(List<havis.util.monitor.Configuration> configs) {
		if (!connected)
			return;

		for (havis.util.monitor.Configuration cfg : configs) {
			if (cfg instanceof AntennaConfiguration) {
				AntennaConfiguration aCfg = (AntennaConfiguration) cfg;

				try {
					setAntennaConfig(aCfg);
				} catch (ConnectionException | havis.device.rf.exception.ImplementationException | havis.device.rf.exception.ParameterException
						| IllegalArgumentException e) {
					log.log(Level.SEVERE, "Failed to set antenna configuration.", e);
					clientCallback.notify(new Message(Exits.Reader.Controller.Error, "Failed to set antenna configuration: " + e.getMessage(), e));
				}
			}
		}
	}

	private void setAntennaConfig(AntennaConfiguration aCfg) throws havis.device.rf.exception.ConnectionException,
			havis.device.rf.exception.ImplementationException, havis.device.rf.exception.ParameterException, IllegalArgumentException {

		if (aCfg.getId() == (short) 0) {
			setMultipleAntennaConfig(aCfg);
			return;
		}

		List<havis.device.rf.configuration.Configuration> configList = this.rfDevice
				.getConfiguration(havis.device.rf.configuration.ConfigurationType.ANTENNA_CONFIGURATION, aCfg.getId(), (short) 0, (short) 0);

		if (configList.size() == 0)
			throw new IllegalArgumentException("No antenna configuration exists for the specified antenna ID.");

		havis.device.rf.configuration.AntennaConfiguration rfAntConf = (havis.device.rf.configuration.AntennaConfiguration) configList.get(0);

		switch (aCfg.getConnect()) {
		case AUTO:
			if (rfAntConf.getConnect() == ConnectType.AUTO)
				return;
			rfAntConf.setConnect(ConnectType.AUTO);
			break;
		case TRUE:
			if (rfAntConf.getConnect() == ConnectType.TRUE)
				return;
			rfAntConf.setConnect(ConnectType.TRUE);
			break;
		case FALSE:
			if (rfAntConf.getConnect() == ConnectType.FALSE)
				return;
			rfAntConf.setConnect(ConnectType.FALSE);
			break;
		}

		rfDevice.setConfiguration(configList);
	}

	private void setMultipleAntennaConfig(AntennaConfiguration aCfg) throws havis.device.rf.exception.ConnectionException,
			havis.device.rf.exception.ImplementationException, havis.device.rf.exception.ParameterException {

		List<havis.device.rf.configuration.Configuration> configList = this.rfDevice
				.getConfiguration(havis.device.rf.configuration.ConfigurationType.ANTENNA_CONFIGURATION, (short) 0, (short) 0, (short) 0);

		List<havis.device.rf.configuration.Configuration> changedConfigs = new ArrayList<>();

		for (havis.device.rf.configuration.Configuration rfConf : configList) {
			havis.device.rf.configuration.AntennaConfiguration rfAntConf = (havis.device.rf.configuration.AntennaConfiguration) rfConf;
			switch (aCfg.getConnect()) {
			case AUTO:
				if (rfAntConf.getConnect() == ConnectType.AUTO)
					continue;
				rfAntConf.setConnect(ConnectType.AUTO);
				break;
			case TRUE:
				if (rfAntConf.getConnect() == ConnectType.TRUE)
					continue;
				rfAntConf.setConnect(ConnectType.TRUE);
				break;
			case FALSE:
				if (rfAntConf.getConnect() == ConnectType.FALSE)
					continue;
				rfAntConf.setConnect(ConnectType.FALSE);
				break;
			}
			changedConfigs.add(rfAntConf);
		}

		if (!changedConfigs.isEmpty())
			rfDevice.setConfiguration(changedConfigs);
	}

	protected ConnectionError lastConError;

	private void notifyEvent(ReaderEvent event) {
		this.clientCallback.notify(event);
	}

	protected void notifyConnectionError(String msg) {
		this.lastConError = new ConnectionError(new Date(), true, msg);
		notifyEvent(this.lastConError);
	}

	protected void notifyConnectionErrorResolved() {
		if (this.lastConError != null) {
			this.lastConError.setTimestamp(new Date());
			this.lastConError.setState(false);
			notifyEvent(this.lastConError);
			this.lastConError = null;
		}
	}
}
