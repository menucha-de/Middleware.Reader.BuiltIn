package havis.middleware.reader.rf_r300;

import havis.device.io.State;

import java.util.Timer;

/**
 * Timer with state to reset to
 */
public class StateResetTimer extends Timer {

	private State state;

	/**
	 * Creates a new instance
	 * 
	 * @param state
	 *            the state
	 */
	public StateResetTimer(State state) {
		super();
		this.state = state;
	}

	/**
	 * @return the state
	 */
	public State getState() {
		return this.state;
	}
}
