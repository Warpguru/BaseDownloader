package edu.java.util;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;

import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;
import javax.json.bind.JsonbException;

/**
 * Helper to cache a single {@link Jsonb} instance for the lifetime of the application.
 * <p>
 * {@link Jsonb} instances are relatively expensive to create; callers must always obtain the
 * shared instance via {@link #getInstance()} rather than creating new {@link JsonbBuilder}
 * instances.
 * </p>
 */
public class JsonbUtil implements Jsonb {

	// Cache Jsonb objects whenever possible. They are relatively expensive to create
	private static final Jsonb jsonb = JsonbBuilder.create();

	/**
	 * Hidden constructor — use {@link #getInstance()}.
	 */
	private JsonbUtil() {
	}

	/**
	 * Retrieve the cached {@link Jsonb} implementation.
	 *
	 * @return shared {@link Jsonb} instance
	 */
	public static Jsonb getInstance() {
		return jsonb;
	}

	@Override
	public void close() throws Exception {
		jsonb.close();
	}

	@Override
	public <T> T fromJson(String arg0, Class<T> arg1) throws JsonbException {
		return jsonb.fromJson(arg0, arg1);
	}

	@Override
	public <T> T fromJson(String arg0, Type arg1) throws JsonbException {
		return jsonb.fromJson(arg0, arg1);
	}

	@Override
	public <T> T fromJson(Reader arg0, Class<T> arg1) throws JsonbException {
		return jsonb.fromJson(arg0, arg1);
	}

	@Override
	public <T> T fromJson(Reader arg0, Type arg1) throws JsonbException {
		return jsonb.fromJson(arg0, arg1);
	}

	@Override
	public <T> T fromJson(InputStream arg0, Class<T> arg1) throws JsonbException {
		return jsonb.fromJson(arg0, arg1);
	}

	@Override
	public <T> T fromJson(InputStream arg0, Type arg1) throws JsonbException {
		return jsonb.fromJson(arg0, arg1);
	}

	@Override
	public String toJson(Object arg0) throws JsonbException {
		return jsonb.toJson(arg0);
	}

	@Override
	public String toJson(Object arg0, Type arg1) throws JsonbException {
		return jsonb.toJson(arg0, arg1);
	}

	@Override
	public void toJson(Object arg0, Writer arg1) throws JsonbException {
		jsonb.toJson(arg0, arg1);
	}

	@Override
	public void toJson(Object arg0, OutputStream arg1) throws JsonbException {
		jsonb.toJson(arg0, arg1);
	}

	@Override
	public void toJson(Object arg0, Type arg1, Writer arg2) throws JsonbException {
		jsonb.toJson(arg0, arg1, arg2);
	}

	@Override
	public void toJson(Object arg0, Type arg1, OutputStream arg2) throws JsonbException {
		jsonb.toJson(arg0, arg1, arg2);
	}

}
