package havis.middleware.reader.rf_r300.osgi;

import havis.device.io.IODevice;
import havis.device.rf.RFDevice;
import havis.middleware.ale.base.exception.ImplementationException;
import havis.middleware.ale.reader.ImmutableReaderConnector;
import havis.middleware.ale.reader.ReaderConnector;
import havis.middleware.reader.rf_r300.RF_R300ReaderConnector;

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.util.tracker.ServiceTracker;

public class Activator implements BundleActivator {

	Logger log = Logger.getLogger(Activator.class.getName());

	private static final String NAME = "name";
	private static final String VALUE = "BuiltIn";

	RFDevice rf;
	IODevice io;

	ServiceTracker<RFDevice, RFDevice> rfTracker;
	ServiceTracker<IODevice, IODevice> ioTracker;

	private ServiceRegistration<ImmutableReaderConnector> registration;

	@Override
	public void start(BundleContext context) throws Exception {
		new RF_R300ReaderConnector(rf, io);
		rfTracker = new ServiceTracker<RFDevice, RFDevice>(context, RFDevice.class, null) {
			@Override
			public RFDevice addingService(ServiceReference<RFDevice> reference) {
				synchronized (Activator.this) {
					rf = super.addingService(reference);
					if (rf != null && io != null)
						registerService(context);
					return rf;
				}
			}

			@Override
			public void removedService(ServiceReference<RFDevice> reference, RFDevice service) {
				synchronized (Activator.this) {
					rf = null;
					if (io != null)
						unregisterService(context);
					super.removedService(reference, service);
				}
			}
		};
		log.log(Level.FINE, "Opening RF tracker {0}.", rfTracker.getClass().getName());
		rfTracker.open();

		ioTracker = new ServiceTracker<IODevice, IODevice>(context, IODevice.class, null) {
			@Override
			public IODevice addingService(ServiceReference<IODevice> reference) {
				synchronized (Activator.this) {
					io = super.addingService(reference);
					if (rf != null && io != null)
						registerService(context);
					return io;
				}
			}

			@Override
			public void removedService(ServiceReference<IODevice> reference, IODevice service) {
				synchronized (Activator.this) {
					io = null;
					if (rf != null)
						unregisterService(context);
					super.removedService(reference, service);
				}
			}
		};
		log.log(Level.FINE, "Opening IO tracker {0}.", ioTracker.getClass().getName());
		ioTracker.open();
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		synchronized (Activator.this) {
			unregisterService(context);
			log.log(Level.FINE, "Closing RF tracker {0}.", rfTracker.getClass().getName());
			rfTracker.close();
			log.log(Level.FINE, "Closing IO tracker {0}.", ioTracker.getClass().getName());
			ioTracker.close();
		}
	}

	protected void registerService(BundleContext context) {
		Dictionary<String, String> properties = new Hashtable<>();
		properties.put(NAME, VALUE);

		log.log(Level.FINE, "Register service {0} ({1}={2})", new Object[] { ReaderConnector.class.getName(), NAME, VALUE });
		registration = context.registerService(ImmutableReaderConnector.class, new RF_R300ReaderConnector(rf, io), properties);
	}

	protected void unregisterService(BundleContext context) {
		if (registration != null) {
			log.log(Level.FINE, "Unregister service {0}.", ReaderConnector.class.getName());
			ReaderConnector service = context.getService(registration.getReference());
			registration.unregister();
			try {
				service.dispose();
			} catch (ImplementationException e) {
				log.log(Level.SEVERE, "Failed to dispose immutable reader", e);
			}
			registration = null;
		}
	}
}