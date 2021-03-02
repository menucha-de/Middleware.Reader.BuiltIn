package havis.middleware.reader.rf_r300;

import havis.device.rf.RFConsumer;
import havis.device.rf.tag.TagData;
import havis.device.rf.tag.operation.TagOperation;

import java.util.List;

public class RF_R300Consumer implements RFConsumer {

	@Override
	public void connectionAttempted() {
	}

	@Override
	public List<TagOperation> getOperations(TagData arg0) {
		return null;
	}

	@Override
	public void keepAlive() {
	}

}
