package com.alibaba.datax.common.element;

import com.alibaba.datax.common.exception.CommonErrorCode;
import com.alibaba.datax.common.exception.DataXException;
import org.apache.commons.lang3.math.NumberUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Date;

public class IntegerColumn extends Column {

	/**
	 * 从整形字符串表示转为intColumn，支持Java科学计数法
	 *
	 * NOTE: <br>
	 * 如果data为浮点类型的字符串表示，数据将会失真，请使用DoubleColumn对接浮点字符串
	 *
	 * */
	public IntegerColumn(final String data) {
		super(null, Type.INT, 0);
		if (null == data) {
			return;
		}

		try {

			Integer rawData = NumberUtils.createInteger(data);
			super.setRawData(rawData);

			// 当 rawData 为[0-127]时，rawData.bitLength() < 8，导致其 byteSize = 0，简单起见，直接认为其长度为 data.length()
			// super.setByteSize(rawData.bitLength() / 8);
			super.setByteSize(data.length());
		} catch (Exception e) {
			throw DataXException.asDataXException(
					CommonErrorCode.CONVERT_NOT_SUPPORT,
					String.format("String[%s]不能转为INT .", data));
		}
	}

	public IntegerColumn(Long data) {
		this(null == data ? (BigInteger) null : BigInteger.valueOf(data));
	}

	public IntegerColumn(Integer data) {
		this(null == data ? (BigInteger) null : BigInteger.valueOf(data));
	}

	public IntegerColumn(BigInteger data) {
		this(data, null == data ? 0 : 8);
	}

	private IntegerColumn(BigInteger data, int byteSize) {
		super(data, Type.LONG, byteSize);
	}

	public IntegerColumn() {
		this((BigInteger) null);
	}

	@Override
	public BigInteger asBigInteger() {
		if (null == this.getRawData()) {
			return null;
		}

		return (BigInteger) this.getRawData();
	}

	@Override
	public Long asLong() {
		BigInteger rawData = (BigInteger) this.getRawData();
		if (null == rawData) {
			return null;
		}

		OverFlowUtil.validateLongNotOverFlow(rawData);

		return rawData.longValue();
	}

	@Override
	public Double asDouble() {
		if (null == this.getRawData()) {
			return null;
		}

		BigDecimal decimal = this.asBigDecimal();
		OverFlowUtil.validateDoubleNotOverFlow(decimal);

		return decimal.doubleValue();
	}

	@Override
	public Boolean asBoolean() {
		if (null == this.getRawData()) {
			return null;
		}

		return this.asBigInteger().compareTo(BigInteger.ZERO) != 0 ? true
				: false;
	}

	@Override
	public BigDecimal asBigDecimal() {
		if (null == this.getRawData()) {
			return null;
		}

		return new BigDecimal(this.asBigInteger());
	}

	@Override
	public String asString() {
		if (null == this.getRawData()) {
			return null;
		}
		return ((BigInteger) this.getRawData()).toString();
	}

	@Override
	public Date asDate() {
		if (null == this.getRawData()) {
			return null;
		}
		return new Date(this.asLong());
	}
	
	@Override
	public Date asDate(String dateFormat) {
		return this.asDate();
	}

	@Override
	public byte[] asBytes() {
		throw DataXException.asDataXException(
				CommonErrorCode.CONVERT_NOT_SUPPORT, "Long类型不能转为Bytes .");
	}

}
