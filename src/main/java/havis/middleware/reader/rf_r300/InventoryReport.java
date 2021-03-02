package havis.middleware.reader.rf_r300;

import havis.middleware.ale.base.operation.tag.Tag;

class InventoryReport {
	Tag tag;
	havis.middleware.ale.base.operation.tag.result.ReadResult[] readResult;

	InventoryReport(Tag tag, havis.middleware.ale.base.operation.tag.result.ReadResult[] readResult) {
		this.tag = tag;
		this.readResult = readResult;
	}

	/**
	 * @return the tag
	 */
	public Tag getTag() {
		return tag;
	}

	/**
	 * @param tag
	 *            the tag to set
	 */
	public void setTag(Tag tag) {
		this.tag = tag;
	}

	/**
	 * @return the readResult
	 */
	public havis.middleware.ale.base.operation.tag.result.ReadResult[] getReadResult() {
		return readResult;
	}

	/**
	 * @param readResult
	 *            the readResult to set
	 */
	public void setReadResult(havis.middleware.ale.base.operation.tag.result.ReadResult[] readResult) {
		this.readResult = readResult;
	}
}

