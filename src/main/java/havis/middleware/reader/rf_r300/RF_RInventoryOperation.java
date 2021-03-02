package havis.middleware.reader.rf_r300;

/**
 * Class provides objects to hold RF_R connector inventory informations.
 */
public class RF_RInventoryOperation implements Cloneable {
	private boolean reserved;
	private boolean epc;
    private boolean tid;
    private UserReadMode user;
    private boolean[] userDataBlocks;

    public enum UserReadMode {
    	OFF, ON, ON_COMPLETE
    }

	/**
     * Initializes a new instance of the {@link RF_RInventoryOperation} class.
     */
    public RF_RInventoryOperation() {
		super();
	}

	/**
     * Gets an indicator if reserved bank should be read during inventory.
	 * @return 
	 */
	public boolean isReserved() {
		return reserved;
	}

	/**
	 * Sets an indicator if reserved bank should be read during inventory.
	 * @param reserved 
	 */
	public void setReserved(boolean reserved) {
		this.reserved = reserved;
	}

	/**
	 * Gets an indicator if EPC bank should be read during inventory.
	 * @return 
	 */
	public boolean isEpc() {
		return epc;
	}

	/**
	 * Sets an indicator if EPC bank should be read during inventory.
	 * @param epc 
	 */
	public void setEpc(boolean epc) {
		this.epc = epc;
	}

	/**
	 * Gets an indicator if TID bank should be read during inventory.
	 * @return 
	 */
	public boolean isTid() {
		return tid;
	}

	/**
	 * Sets an indicator if TID bank should be read during inventory.
	 * @param tid 
	 */
	public void setTid(boolean tid) {
		this.tid = tid;
	}

	/**
	 * Gets an indicator if user bank should be read during inventory.
	 * @return the user
	 */
	public boolean isUser() {
		return user == UserReadMode.ON || user == UserReadMode.ON_COMPLETE;
	}

	/**
	 * Gets an indicator if user bank should be read completely during inventory.
	 * @return the indicator
	 */
	public boolean isForceUserComplete() {
		return user == UserReadMode.ON_COMPLETE;
	}

	/**
	 * Sets an indicator if user bank should be read during inventory.
	 * @param user
	 */
	public void setUser(UserReadMode user) {
		this.user = user;
	}
	
	/**
	 * Gets the data blocks that will be read of user bank.
	 * @return
	 */
	public boolean[] getUserDataBlocks() {
		return userDataBlocks;
	}

	/**
	 * Sets the data blocks that will be read of user bank.
	 * @param userDataBlocks
	 */
	public void setUserDataBlocks(boolean[] userDataBlocks) {
		this.userDataBlocks = userDataBlocks;
	}

	@Override
	protected RF_RInventoryOperation clone() {
		try {
			return (RF_RInventoryOperation) super.clone();
		} catch (CloneNotSupportedException e) {
			throw new IllegalStateException(e);
		}
	}

	@Override
	public String toString() {
		return "RF_RInventoryOperation [epc=" + epc + ", tid=" + tid + ", user=" + user + "]";
	}
}
