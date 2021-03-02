package havis.middleware.reader.rf_r300;

import havis.device.rf.common.util.RFUtils;
import havis.device.rf.tag.Filter;
import havis.device.rf.tag.operation.CustomOperation;
import havis.device.rf.tag.operation.KillOperation;
import havis.device.rf.tag.operation.LockOperation;
import havis.device.rf.tag.operation.LockOperation.Field;
import havis.device.rf.tag.operation.LockOperation.Privilege;
import havis.device.rf.tag.operation.ReadOperation;
import havis.device.rf.tag.operation.WriteOperation;
import havis.device.rf.tag.result.CustomResult;
import havis.device.rf.tag.result.KillResult;
import havis.device.rf.tag.result.LockResult;
import havis.device.rf.tag.result.OperationResult;
import havis.device.rf.tag.result.ReadResult;
import havis.device.rf.tag.result.ReadResult.Result;
import havis.device.rf.tag.result.WriteResult;
import havis.middleware.ale.base.operation.tag.LockType;
import havis.middleware.ale.base.operation.tag.Operation;
import havis.middleware.ale.base.operation.tag.TagOperation;
import havis.middleware.ale.base.operation.tag.result.ResultState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TypeConverter {

	static havis.middleware.ale.base.operation.tag.result.ReadResult convert(
			ResultState state, byte[] data) {
		return new havis.middleware.ale.base.operation.tag.result.ReadResult(
				ResultState.MISC_ERROR_TOTAL, data);
	}
	
	static havis.middleware.ale.base.operation.tag.result.ReadResult convert(
			ReadResult srcReadRes, 
			havis.middleware.ale.base.operation.tag.result.ReadResult trgReadRes, 
			havis.device.rf.tag.operation.ReadOperation srcOperation) {
		
		/* if an instance of a read result has been passed, use that one,
		 * otherwise, create a new one. */
		havis.middleware.ale.base.operation.tag.result.ReadResult ret = 
			trgReadRes == null ? 
			new havis.middleware.ale.base.operation.tag.result.ReadResult() :
			trgReadRes;

		/* set the state of the read result (this method returns) to the one provided
		 * by the source read result */
		ret.setState(convert(srcReadRes.getResult()));
		
		/* if read result was success, save the read data in the result to be returned */
		if (srcReadRes.getResult() == Result.SUCCESS) {
			/* the source read data */
			byte[] srcData = srcReadRes.getReadData();
			
			/* the read data from the result so far */
			byte[] trgData = ret.getData();
			
			/* if no such data exists yet, create it with the initial size that is the 
			 * sum of source data length and the offset specified in the source operation */
			if (trgData == null)
				trgData = new byte[srcData.length + srcOperation.getOffset() * 2];				
			
			/* otherwise copy the data to the existing array */
			else {
				/* calculate the required array length for the target array */
				
				//no length in read op specified ?
				int srcDataLen = srcOperation.getLength() == 0 ?  
					
					// use the sum of actual array length of the result data and the offset:
					(srcReadRes.getReadData().length + srcOperation.getOffset() * 2) : 

					// use the sum of length and offset
					(srcOperation.getLength() * 2 + srcOperation.getOffset() * 2); 
				
				// if target array is too small, copy it into a bigger one
				if (trgData.length < srcDataLen)
					trgData = Arrays.copyOf(trgData, srcDataLen);
				
				
			}
			
			// copy the source data to the possibly resized target array at the 
			// offset specified in the source operation
			for (int i = 0; i < srcData.length; i++)
				trgData[i + srcOperation.getOffset() * 2] = srcData[i];
			
			/* save the new or changed read data array in the result */
			ret.setData(trgData);
						
		}
		/* if the source read result was no success, delete the possibly incomplete read data and return */
		else
			ret.setData(null);

		/* return the new or changed read result */
		return ret;

	}

	static havis.middleware.ale.base.operation.tag.result.Result convert(OperationResult opRes) {
		if (opRes instanceof ReadResult) {
			ReadResult rr = (ReadResult) opRes;
			return new havis.middleware.ale.base.operation.tag.result.ReadResult(convert(rr.getResult()), rr.getReadData());
		}
		if (opRes instanceof WriteResult) {
			WriteResult wr = (WriteResult) opRes;
			return new havis.middleware.ale.base.operation.tag.result.WriteResult(convert(wr.getResult()), wr.getWordsWritten());
		}
		if (opRes instanceof LockResult) {
			LockResult lr = (LockResult) opRes;
			return new havis.middleware.ale.base.operation.tag.result.LockResult(convert(lr.getResult()));
		}
		if (opRes instanceof KillResult) {
			KillResult kr = (KillResult) opRes;
			return new havis.middleware.ale.base.operation.tag.result.KillResult(convert(kr.getResult()));
		}
		if (opRes instanceof CustomResult) {
			CustomResult cr = (CustomResult) opRes;
			return new havis.middleware.ale.base.operation.tag.result.CustomResult(convert(cr.getResult()), cr.getResultData());
		}
		return null;
	}

	static ResultState convert(ReadResult.Result rdRes) {
		switch (rdRes) {

		case SUCCESS:
			return ResultState.SUCCESS;

		case INCORRECT_PASSWORD_ERROR:
			return ResultState.PASSWORD_ERROR;

		case MEMORY_LOCKED_ERROR:
			return ResultState.PERMISSION_ERROR;

		case MEMORY_OVERRUN_ERROR:
			return ResultState.MEMORY_OVERFLOW_ERROR;

		case NO_RESPONSE_FROM_TAG:
		case NON_SPECIFIC_READER_ERROR:
		case NON_SPECIFIC_TAG_ERROR:

		default:
			return ResultState.MISC_ERROR_TOTAL;
		}
	}

	static ResultState convert(WriteResult.Result wrRes) {
		switch (wrRes) {

		case SUCCESS:
			return ResultState.SUCCESS;

		case INCORRECT_PASSWORD_ERROR:
			return ResultState.PASSWORD_ERROR;

		case MEMORY_LOCKED_ERROR:
			return ResultState.PERMISSION_ERROR;

		case MEMORY_OVERRUN_ERROR:
			return ResultState.MEMORY_OVERFLOW_ERROR;

		case INSUFFICIENT_POWER:
		case NO_RESPONSE_FROM_TAG:
		case NON_SPECIFIC_READER_ERROR:
		case NON_SPECIFIC_TAG_ERROR:

		default:
			return ResultState.MISC_ERROR_TOTAL;

		}
	}

	static ResultState convert(LockResult.Result lkRes) {
		switch (lkRes) {

		case SUCCESS:
			return ResultState.SUCCESS;

		case INCORRECT_PASSWORD_ERROR:
			return ResultState.PASSWORD_ERROR;

		case MEMORY_LOCKED_ERROR:
			return ResultState.PERMISSION_ERROR;

		case MEMORY_OVERRUN_ERROR:
			return ResultState.MEMORY_OVERFLOW_ERROR;

		case INSUFFICIENT_POWER:
		case NO_RESPONSE_FROM_TAG:
		case NON_SPECIFIC_READER_ERROR:
		case NON_SPECIFIC_TAG_ERROR:

		default:
			return ResultState.MISC_ERROR_TOTAL;

		}
	}

	static ResultState convert(KillResult.Result lkRes) {
		switch (lkRes) {

		case SUCCESS:
			return ResultState.SUCCESS;

		case INCORRECT_PASSWORD_ERROR:
			return ResultState.PASSWORD_ERROR;

		case INSUFFICIENT_POWER:
		case NO_RESPONSE_FROM_TAG:
		case NON_SPECIFIC_READER_ERROR:
		case NON_SPECIFIC_TAG_ERROR:

		default:
			return ResultState.MISC_ERROR_TOTAL;
		}
	}
	
	static ResultState convert(CustomResult.Result csRes) {
		switch (csRes) {

		case SUCCESS:
			return ResultState.SUCCESS;

		case INCORRECT_PASSWORD_ERROR:
			return ResultState.PASSWORD_ERROR;

		case MEMORY_LOCKED_ERROR:
			return ResultState.PERMISSION_ERROR;

		case MEMORY_OVERRUN_ERROR:
			return ResultState.MEMORY_OVERFLOW_ERROR;

		case OP_NOT_POSSIBLE_ERROR:
			return ResultState.OP_NOT_POSSIBLE_ERROR;

		case INSUFFICIENT_POWER:
		case NO_RESPONSE_FROM_TAG:
		case NON_SPECIFIC_READER_ERROR:
		case NON_SPECIFIC_TAG_ERROR:
			
		default:
			return ResultState.MISC_ERROR_TOTAL;

		}
	}

	static byte[] createFilterMask(int len) {
		byte[] mask = new byte[len];
		for (int i = 0; i < mask.length; i++)
			mask[i] = (byte) 0xff;
		return mask;
	}

	static List<Filter> generateFilters(TagOperation tagOp) {

		List<Filter> ret = new ArrayList<>();
		/*
		 * create filters based on the filter array of the tagOperation
		 */
		if (tagOp.getFilter() != null) {
			for (final havis.middleware.ale.base.operation.tag.Filter srcFilter : tagOp.getFilter()) {
				final byte[] mask = createFilterMask(srcFilter.getMask().length);
				Filter f = new Filter();
				f.setBank((short) srcFilter.getBank());
				f.setBitLength((short) srcFilter.getLength());
				f.setBitOffset((short) srcFilter.getOffset());
				f.setMask(mask);
				f.setData(srcFilter.getMask());
				f.setMatch(true);
				ret.add(f);
			}
		}
		return ret;
	}

	static List<havis.device.rf.tag.operation.TagOperation> convert(
			TagOperation tagOp) {
		int password = 0;
		List<havis.device.rf.tag.operation.TagOperation> ret = new ArrayList<>();

		for (final Operation op : tagOp.getOperations()) {
			switch (op.getType()) {
			case READ:
				ReadOperation rdOp = new ReadOperation();
				rdOp.setOperationId(op.getId() + "");
				rdOp.setBank((short) op.getField().getBank());
				rdOp.setLength(toWords(op.getField().getLength()));
				rdOp.setOffset(toWords(op.getField().getOffset()));
				rdOp.setPassword(password);
				ret.add(rdOp);
				break;

			case WRITE:
				WriteOperation wrOp = new WriteOperation();
				wrOp.setOperationId(op.getId() + "");
				wrOp.setData(op.getData());
				wrOp.setBank((short) op.getField().getBank());
				wrOp.setOffset(toWords(op.getField().getOffset()));
				wrOp.setPassword(password);
				ret.add(wrOp);
				break;

			case LOCK:
				LockOperation lkOp = new LockOperation();
				lkOp.setOperationId(op.getId() + "");

				switch (op.getField().getBank()) {
				case RFUtils.BANK_PSW:
					if (op.getField().getOffset() == 0
							&& op.getField().getLength() == 32)
						lkOp.setField(Field.KILL_PASSWORD);
					else if (op.getField().getOffset() == 32
							&& op.getField().getLength() == 32)
						lkOp.setField(Field.ACCESS_PASSWORD);
					break;

				case RFUtils.BANK_EPC:
					lkOp.setField(Field.EPC_MEMORY);
					break;

				case RFUtils.BANK_TID:
					lkOp.setField(Field.TID_MEMORY);
					break;

				case RFUtils.BANK_USR:
					lkOp.setField(Field.USER_MEMORY);
					break;
				}

				LockType lockType = LockType.values()[op.getData()[0]]; 
				switch (lockType) {
				case LOCK:
					lkOp.setPrivilege(Privilege.LOCK);
					break;

				case PERMALOCK:
					lkOp.setPrivilege(Privilege.PERMALOCK);
					break;

				case PERMAUNLOCK:
					lkOp.setPrivilege(Privilege.PERMAUNLOCK);
					break;

				case UNLOCK:
					lkOp.setPrivilege(Privilege.UNLOCK);
					break;
				}

				lkOp.setPassword(password);
				ret.add(lkOp);

				break;

			case PASSWORD:
				password = RFUtils.bytesToInt(op.getData());

				// add anonymous pseudo-op to generate result later
				ret.add(new havis.device.rf.tag.operation.TagOperation() {
					{
						setOperationId("psw-" + op.getId());
					}
				});
				break;

			case KILL:
				KillOperation klOp = new KillOperation();
				klOp.setKillPassword(RFUtils.bytesToInt(op.getData()));
				klOp.setOperationId(op.getId() + "");
				ret.add(klOp);
				break;
				
			case CUSTOM:
				CustomOperation cOp = new CustomOperation();
				cOp.setData(op.getData());
				cOp.setLength((short)op.getBitLength());
				cOp.setOperationId(op.getId() + "");
				cOp.setPassword(password);	
				ret.add(cOp);
			}
		}
		return ret;
	}

	private static short toWords(int bitCount) {
		return (short) (bitCount / 16);
	}

}
