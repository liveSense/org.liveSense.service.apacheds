package org.liveSense.service.apacheds;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InvalidObjectException;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Stack;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.directory.api.i18n.I18n;
import org.apache.directory.api.ldap.model.constants.SchemaConstants;
import org.apache.directory.api.ldap.model.exception.LdapException;
import org.apache.directory.api.ldap.model.ldif.LdapLdifException;
import org.apache.directory.api.ldap.model.ldif.LdifEntry;
import org.apache.directory.api.ldap.model.ldif.LdifReader;
import org.apache.directory.api.ldap.schemaextractor.SchemaLdifExtractor;
import org.apache.directory.api.ldap.schemaextractor.UniqueResourceException;
import org.apache.directory.api.ldap.schemaextractor.impl.ResourceMap;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class OsgiSchemaLdiffExtractor implements SchemaLdifExtractor{
	private static final String BASE_PATH = "";

	private static final String SCHEMA_SUBDIR = "schema";

	private static final Logger log = LoggerFactory.getLogger( OsgiSchemaLdiffExtractor.class );

	private boolean extracted;

	private final File outputDirectory;

	private final File schemaDirectory;

	private final BundleContext context;

	/**
	 * Creates an extractor which deposits files into the specified output
	 * directory.
	 *
	 * @param outputDirectory the directory where the schema root is extracted
	 */
	public OsgiSchemaLdiffExtractor(BundleContext context, File outputDirectory )
	{
		log.debug( "BASE_PATH set to {}, outputDirectory set to {}", BASE_PATH, outputDirectory );
		this.outputDirectory = outputDirectory;
		this.schemaDirectory = new File( outputDirectory, SCHEMA_SUBDIR );
		this.context = context;

		if ( ! outputDirectory.exists() )
		{
			log.debug( "Creating output directory: {}", outputDirectory );
			if( ! outputDirectory.mkdir() )
			{
				log.error( "Failed to create outputDirectory: {}", outputDirectory );
			}
		}
		else
		{
			log.debug( "Output directory exists: no need to create." );
		}

		if ( ! schemaDirectory.exists() )
		{
			log.debug("->>>> does NOT exist extracted set to false");
			log.info( "Schema directory '{}' does NOT exist: extracted state set to false.", schemaDirectory );
			extracted = false;
		}
		else
		{
			log.debug("->>>> does exist extracted set to true");
			log.info( "Schema directory '{}' does exist: extracted state set to true.", schemaDirectory );
			extracted = true;
		}
	}


	/**
	 * Gets whether or not schema folder has been created or not.
	 *
	 * @return true if schema folder has already been extracted.
	 */
	@Override
	public boolean isExtracted()
	{
		return extracted;
	}

	private  HashMap<String, Boolean> getResourcesFromBundle(
			BundleContext context, Pattern pattern ) {
		HashMap<String, Boolean> retval = new HashMap<String, Boolean>();
		try
		{
			ClassLoader cl = ResourceMap.class.getClassLoader();
			Enumeration<URL> indexes = cl.getResources( "META-INF/apacheds-schema.index" );
			while ( indexes.hasMoreElements() )
			{
				URL index = indexes.nextElement();
				InputStream in = index.openStream();
				BufferedReader reader = new BufferedReader( new InputStreamReader( in, "UTF-8" ) );
				String line = reader.readLine();
				while ( line != null )
				{
					boolean accept = pattern.matcher( line ).matches();
					if ( accept )
					{
						retval.put( line, Boolean.TRUE );
					}
					line = reader.readLine();
				}
				reader.close();
			}
		}
		catch ( IOException e )
		{
			throw new Error( e );
		}
		return retval;
	}

	/**
	 * Extracts the LDIF files from a Jar file or copies exploded LDIF resources.
	 * 
	 * @param bundleContext the osgi context
	 * @param overwrite over write extracted structure if true, false otherwise
	 * @throws IOException if schema already extracted and on IO errors
	 */
	@Override
	public void extractOrCopy(boolean overwrite ) throws IOException
	{
		log.debug("-->start extractOrCopy");
		log.debug("--> outputDirectory.exists()"+outputDirectory.exists());

		if ( ! outputDirectory.exists() )
		{
			outputDirectory.mkdir();
		}

		File schemaDirectory = new File( outputDirectory, SCHEMA_SUBDIR );
		log.debug("--> schemaDirectory(getAbsolutePath)="+schemaDirectory.getAbsolutePath());
		log.debug("--> schemaDirectory.exists()="+schemaDirectory.exists());

		if ( ! schemaDirectory.exists() )
		{
			schemaDirectory.mkdir();
		}
		else if ( ! overwrite )
		{
			log.debug("-->  else if ( ! overwrite )");
			throw new IOException( I18n.err( I18n.ERR_08001, schemaDirectory.getAbsolutePath() ) );
		}

		Pattern pattern = Pattern.compile( ".*schema/ou=schema.*\\.ldif" );
		Map<String,Boolean> list = getResourcesFromBundle(context, pattern); 

		log.info("ResourceMap size = "+list.size());
		for ( Entry<String,Boolean> entry : list.entrySet() )
		{
			log.debug("--> entry.getValue() ="+entry.getValue());
			if ( entry.getValue() )
			{
				log.debug("---> bundle");
				extractFromBundle(context, entry.getKey() );
			}
			else
			{
				log.debug("---> not jar");
				File resource = new File( entry.getKey() );
				copyFile( resource, getDestinationFile( resource ) );
			}
		}
		
		pattern = Pattern.compile( "directory\\-cacheservice\\.xml" );
		list = getResourcesFromBundle(context, pattern); 
		for ( Entry<String,Boolean> entry : list.entrySet() ) {
			log.info("->"+entry.getKey()+" "+entry.getValue());
			log.debug("--> entry.getValue() ="+entry.getValue());
			if ( entry.getValue() )
			{
				log.debug("---> bundle");
				extractFromBundle(context, entry.getKey() );
			}
			else
			{
				log.debug("---> not jar");
				File resource = new File( entry.getKey() );
				copyFile( resource, getDestinationFile( resource ) );
			}
			
		}

	}


	/**
	 * Extracts the LDIF files from a Jar file or copies exploded LDIF
	 * resources without overwriting the resources if the schema has
	 * already been extracted.
	 *
	 * @throws IOException if schema already extracted and on IO errors
	 */
	@Override
	public void extractOrCopy() throws IOException
	{
		extractOrCopy( false );
	}


	/**
	 * Copies a file line by line from the source file argument to the 
	 * destination file argument.
	 *
	 * @param source the source file to copy
	 * @param destination the destination to copy the source to
	 * @throws IOException if there are IO errors or the source does not exist
	 */
	private void copyFile( File source, File destination ) throws IOException
	{
		log.info( "copyFile(): source = {}, destination = {}", source, destination );

		if ( ! destination.getParentFile().exists() )
		{
			destination.getParentFile().mkdirs();
		}

		if ( ! source.getParentFile().exists() )
		{
			throw new FileNotFoundException( I18n.err( I18n.ERR_08002, source.getAbsolutePath() ) );
		}

		FileWriter out = new FileWriter( destination );

		try
		{
			LdifReader ldifReader = new LdifReader( source );
			boolean first = true;
			LdifEntry ldifEntry = null;

			while ( ldifReader.hasNext() )
			{
				if ( first )
				{
					ldifEntry = ldifReader.next();

					if ( ldifEntry.get( SchemaConstants.ENTRY_UUID_AT ) == null )
					{
						// No UUID, let's create one
						UUID entryUuid = UUID.randomUUID();
						ldifEntry.addAttribute( SchemaConstants.ENTRY_UUID_AT, entryUuid.toString() );
					}

					first = false;
				}
				else
				{
					// throw an exception : we should not have more than one entry per schema ldif file
					String msg = I18n.err( I18n.ERR_08003, source );
					log.error( msg );
					throw new InvalidObjectException( msg );
				}
			}

			ldifReader.close();

			// Add the version at the first line, to avoid a warning
			String ldifString = "version: 1\n"+ldifEntry.toString();

			out.write( ldifString );
			out.flush();
		}
		catch ( LdapLdifException ne )
		{
			// throw an exception : we should not have more than one entry per schema ldif file
			String msg = I18n.err( I18n.ERR_08004, source, ne.getLocalizedMessage() );
			log.error( msg );
			throw new InvalidObjectException( msg );
		}
		catch ( LdapException ne )
		{
			// throw an exception : we should not have more than one entry per schema ldif file
			String msg = I18n.err( I18n.ERR_08004, source, ne.getLocalizedMessage() );
			log.error( msg );
			throw new InvalidObjectException( msg );
		}
		finally
		{
			out.close();
		}
	}


	/**
	 * Assembles the destination file by appending file components previously
	 * pushed on the fileComponentStack argument.
	 *
	 * @param fileComponentStack stack containing pushed file components
	 * @return the assembled destination file
	 */
	private File assembleDestinationFile( Stack<String> fileComponentStack )
	{
		File destinationFile = outputDirectory.getAbsoluteFile();

		while ( ! fileComponentStack.isEmpty() )
		{
			destinationFile = new File( destinationFile, fileComponentStack.pop() );
		}

		return destinationFile;
	}


	/**
	 * Calculates the destination file.
	 *
	 * @param resource the source file
	 * @return the destination file's parent directory
	 */
	private File getDestinationFile( File resource )
	{
		File parent = resource.getParentFile();
		Stack<String> fileComponentStack = new Stack<String>();
		fileComponentStack.push( resource.getName() );

		while ( parent != null )
		{
			if ( parent.getName().equals( "schema" ) )
			{
				// All LDIF files besides the schema.ldif are under the 
				// schema/schema base path. So we need to add one more 
				// schema component to all LDIF files minus this schema.ldif
				fileComponentStack.push( "schema" );

				return assembleDestinationFile( fileComponentStack );
			}

			fileComponentStack.push( parent.getName() );

			if ( parent.equals( parent.getParentFile() )
					|| parent.getParentFile() == null )
			{
				throw new IllegalStateException( I18n.err( I18n.ERR_08005 ) );
			}

			parent = parent.getParentFile();
		}

		/*

            this seems retarded so I replaced it for now with what is below it
            will not break from loop above unless parent == null so the if is
            never executed - just the else is executed every time

         if ( parent != null )
         {
             return assembleDestinationFile( fileComponentStack );
         }
         else
         {
             throw new IllegalStateException( "parent cannot be null" );
         }

		 */

		throw new IllegalStateException( I18n.err( I18n.ERR_08006 ) );
	}


	/**
	 * Gets the DBFILE resource from within the OSGi resource. If another jar
	 * with such a DBFILE resource exists then an error will result since the resource
	 * is not unique across all the jars.
	 *
	 * @param bundleContext the osgi context
	 * @param resourceName the file name of the resource to load
	 * @param resourceDescription human description of the resource
	 * @return the InputStream to read the contents of the resource
	 * @throws IOException if there are problems reading or finding a unique copy of the resource
	 */                                                                                                
	public static InputStream getUniqueResourceAsStream(BundleContext context, String resourceName, String resourceDescription ) throws IOException
	{
		resourceName = BASE_PATH+resourceName;
		URL result = getUniqueResource(context, resourceName, resourceDescription );
		return result.openStream();
	}


	/**
	 * Gets a unique resource from a Jar file.
	 * 
	 * @param bundleContext the osgi context
	 * @param resourceName the name of the resource
	 * @param resourceDescription the description of the resource
	 * @return the URL to the resource in the Jar file
	 * @throws IOException if there is an IO error
	 */
	public static URL getUniqueResource(BundleContext context, String resourceName, String resourceDescription )
			throws IOException
			{
		URL result = null;

		int i=0;
		int size=context.getBundles().length;
		while (result == null && i<size) {
			result = context.getBundles()[i].getResource(resourceName);
			i++;
		}
		if (result == null)
			throw new UniqueResourceException( resourceName, resourceDescription );
		
		return result;
	}


	/**
	 * Extracts the LDIF schema resource from a Jar.
	 * @param bundleContext the osgi context
	 * @param resource the LDIF schema resource
	 * @throws IOException if there are IO errors
	 */
	private void extractFromBundle(BundleContext context, String resource ) throws IOException
	{
		log.debug("-->extractFromBundle resource =" + resource);
		byte[] buf = new byte[512];
		InputStream in = OsgiSchemaLdiffExtractor.getUniqueResourceAsStream(context, resource,
				"LDIF file in schema repository" );

		try
		{
			File destination = new File( outputDirectory, resource );
			log.debug("--> destination =" + destination.getAbsolutePath());
			/*
			 * Do not overwrite an LDIF file if it has already been extracted.
			 */
			if ( destination.exists() )
			{
				log.debug("--> destination not exists.");
				return;
			}

			if ( ! destination.getParentFile().exists() )
			{
				destination.getParentFile().mkdirs();
			}

			FileOutputStream out = new FileOutputStream( destination );
			try
			{
				while ( in.available() > 0 )
				{
					int readCount = in.read( buf );
					out.write( buf, 0, readCount );
				}
				out.flush();
			} finally
			{
				out.close();
			}
		}
		finally
		{
			in.close();
		}
	}
}