package org.liveSense.service.apacheds;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;

import org.apache.directory.api.ldap.model.entry.Entry;
import org.apache.directory.api.ldap.model.name.Dn;
import org.apache.directory.api.ldap.model.schema.SchemaManager;
import org.apache.directory.api.ldap.schemaextractor.SchemaLdifExtractor;
import org.apache.directory.api.ldap.schemaloader.LdifSchemaLoader;
import org.apache.directory.api.ldap.schemamanager.impl.DefaultSchemaManager;
import org.apache.directory.server.constants.ServerDNConstants;
import org.apache.directory.server.core.DefaultDirectoryService;
import org.apache.directory.server.core.api.CacheService;
import org.apache.directory.server.core.api.DirectoryService;
import org.apache.directory.server.core.api.InstanceLayout;
import org.apache.directory.server.core.api.partition.Partition;
import org.apache.directory.server.core.api.schema.SchemaPartition;
import org.apache.directory.server.core.partition.impl.btree.jdbm.JdbmIndex;
import org.apache.directory.server.core.partition.impl.btree.jdbm.JdbmPartition;
import org.apache.directory.server.core.partition.ldif.LdifPartition;
import org.apache.directory.server.xdbm.Index;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(label="%ds.service.name", description="%ds.service.description", metatype=true)
public class EmbeddedDirectoryServiceActivator {

	/** The directory service */
	public DirectoryService dservice;
	public ServiceRegistration dserviceReg;
	public CacheService cacheService;
	
	static Logger log = LoggerFactory.getLogger(EmbeddedLdapServerActivator.class);

	private SchemaManager schemaManager = null;

	
	public static String getDsHome(BundleContext bundleContext)
			throws IOException {
		String slingHomePath = bundleContext.getProperty("sling.home");
		File dsHome = new File(slingHomePath, "ds");
		if (!dsHome.isDirectory()) {
			if (!dsHome.mkdirs()) {
				log.info(
						"verifyConfiguration: Cannot create Solr home {}, failed creating default configuration ",
						dsHome.getAbsolutePath());
				return null;
			}
		}
		return dsHome.getAbsolutePath();
	}
	/**
	 * Add a new partition to the server
	 * 
	 * @param partitionId
	 *            The working dir
	 * @param partitionId
	 *            The partition Id
	 * @param partitionDn
	 *            The partition DN
	 * @return The newly added partition
	 * @throws Exception
	 *             If the partition can't be added
	 */
	private Partition addPartition(File workDir, String partitionId, Dn partitionDn)
			throws Exception {

		// Create a new partition named 'foo'.
		JdbmPartition partition = new JdbmPartition(schemaManager);
		partition.setPartitionPath(new File(workDir.getAbsolutePath()+"/partitions", partitionId).toURI());

		partition.setId(partitionId);
		partition.setSuffixDn(partitionDn);
		//dservice.addPartition(partition);

		return partition;
	}

	/**
	 * Add a new set of index on the given attributes
	 * 
	 * @param partition
	 *            The partition on which we want to add index
	 * @param attrs
	 *            The list of attributes to index
	 */
	private void addIndex(Partition partition, String... attrs) {
		// Index some attributes on the apache partition
		HashSet<Index<?, ?, String>> indexedAttributes = new HashSet<Index<?, ?, String>>();
		for (String attribute : attrs) {
			indexedAttributes
			.add(new JdbmIndex<String, Entry>(attribute, false));
		}

		((JdbmPartition) partition).setIndexedAttributes(indexedAttributes);
	}

	/**
	 * initialize the schema manager and add the schema partition to diectory
	 * service
	 * 
	 * @throws Exception
	 *             if the schema LDIF files are not found on the classpath
	 */
	private void initSchemaPartition(BundleContext context, String workingDirectory) throws Exception {
		File schemaRepository = new File( workingDirectory, "schema" );
		SchemaLdifExtractor extractor = new OsgiSchemaLdiffExtractor(context, new File( workingDirectory ) );
		extractor.extractOrCopy( true );
		LdifSchemaLoader loader = new LdifSchemaLoader( schemaRepository );
		schemaManager = new DefaultSchemaManager( loader );

		boolean loaded = schemaManager.loadAllEnabled();
		if ( !loaded ) {
			List<Throwable> errors = schemaManager.getErrors();

			if (errors.size() != 0) {
				throw new Exception("Schema load failed : "+errors);
			} else {
				throw new Exception("Schema load failed! ");
			}
		}

	}

	/**
	 * Initialize the server. It creates the partition, adds the index, and
	 * injects the context entries for the created partitions.
	 * 
	 * @param workDir
	 *            the directory to be used for storing the data
	 * @throws Exception
	 *             if there were some problems while initializing the system
	 */
	private void initDirectoryService(BundleContext context, File workDir, CacheService cacheService) throws Exception {
		log.info("initDirectoryService workdir ="+workDir.getAbsolutePath());
		
		// Initialize the LDAP service
		dservice = new DefaultDirectoryService();
		dservice.setInstanceLayout(new InstanceLayout(workDir));
		dservice.setShutdownHookEnabled(false);
		// first load the schema
		initSchemaPartition(context, workDir.getAbsolutePath()+"/partitions");
		dservice.setSchemaManager(schemaManager);

		// Disable the ChangeLog system
		dservice.getChangeLog().setEnabled(false);
		//dservice.setDenormalizeOpAttrsEnabled(true);
		
		dservice.setCacheService(cacheService);

		SchemaPartition schemaPartition =  new SchemaPartition(schemaManager);
		LdifPartition ldifPartition = new LdifPartition(schemaManager);
		ldifPartition.setPartitionPath(new File(workDir.getAbsolutePath()+"/partitions", "schema").toURI());
		ldifPartition.setId( "schema" );
		ldifPartition.setSuffixDn(  new Dn(schemaManager, ServerDNConstants.CN_SCHEMA_DN));
		schemaPartition.setWrappedPartition(ldifPartition);
		schemaPartition.setId("schema");
		schemaPartition.initialize(); 
		dservice.setSchemaManager(schemaManager);
		dservice.setSchemaPartition(schemaPartition);
		dservice.addPartition(schemaPartition);
		
		// then the system partition
		// this is a MANDATORY partition
		Partition systemPartition = addPartition(workDir, "system", new Dn(schemaManager, ServerDNConstants.SYSTEM_DN));

		dservice.setSystemPartition(systemPartition);
		dservice.getChangeLog().setEnabled(false);
		dservice.setDenormalizeOpAttrsEnabled(false);

/*
		// Disable the ChangeLog system
		dservice.getChangeLog().setEnabled(false);
		dservice.setDenormalizeOpAttrsEnabled(true);
		
		dservice.setCacheService(cacheService);
		dservice.setInstanceLayout(new InstanceLayout(workDir));

		// Now we can create as many partitions as we need
		// Create some new partitions named 'foo', 'bar' and 'apache'.
		Partition apachePartition = addPartition(workDir, "ou=system:apacheRdn", new Dn("dc=apache,dc=org"));
		//dservice.addPartition(apachePartition);

		// Index some attributes on the apache partition
		addIndex(apachePartition, "objectClass", "ou", "uid");

	
		// And start the service
		dservice.startup();

		// Inject the foo root entry if it does not already exist
		/*
		try {
			service.getAdminSession().lookup(fooPartition.getSuffixDn());
		} catch (LdapException lnnfe) {
			DN dnFoo = new DN("dc=foo,dc=com");
			ServerEntry entryFoo = service.newEntry(dnFoo);
			entryFoo.add("objectClass", "top", "domain", "extensibleObject");
			entryFoo.add("dc", "foo");
			service.getAdminSession().add(entryFoo);
		}

		// Inject the bar root entry
		try {
			service.getAdminSession().lookup(barPartition.getSuffixDn());
		} catch (LdapException lnnfe) {
			DN dnBar = new DN("dc=bar,dc=com");
			ServerEntry entryBar = service.newEntry(dnBar);
			entryBar.add("objectClass", "top", "domain", "extensibleObject");
			entryBar.add("dc", "bar");
			service.getAdminSession().add(entryBar);
		}
		 */
		// Inject the apache root entry
		/*
		if (!dservice.getAdminSession().exists(apachePartition.getSuffixDn())) {
			Dn dnApache = new Dn("dc=Apache,dc=Org");
			Entry entryApache = dservice.newEntry(dnApache);
			entryApache.add("objectClass", "top", "domain", "extensibleObject");
			entryApache.add("dc", "Apache");
			dservice.getAdminSession().add(entryApache);
		}*/
		// We are all done !
		dservice.startup();
		
	}


	/**
	 * Creates a new instance of EmbeddedADS. It initializes the directory
	 * service.
	 * 
	 * @throws Exception
	 *             If something went wrong
	 */
	@Activate
	public void activate(BundleContext context) {

		File workDir;
		try {
			workDir = new File(getDsHome(context));
			log.info("schema.resource.location ="+context.getBundle().getSymbolicName()+"("+context.getBundle().getBundleId()+")");

			cacheService = new CacheService();
			initDirectoryService(context, workDir, cacheService);

			// Register as OSGi service
			dserviceReg = context.registerService(
					DirectoryService.class.getName(),
					dservice,
					null);
		} catch (IOException e) {
			log.error("Error activating Apache Directory Service", e);
		} catch (Exception e) {
			log.error("Error activating Apache Directory Service", e);
		}
		
	}


	@Deactivate
	public void deactivate(BundleContext context) {
		try {
			// unRegister OSGi service
			if (dserviceReg != null)
				context.ungetService(dserviceReg.getReference());
			if (dservice != null) {
				if (cacheService != null) {
					try {
						cacheService.destroy();
					} catch(Exception e) {
						log.error("Cache Service shutdown" ,e);
					}
				}

				
				try {
					dservice.shutdown();
				} catch(Exception e) {
					log.error("Directory Service shutdown" ,e);
				}
			} 
		} catch (Exception e) {
			log.error("Error deactivating Apache Directory Service", e);
		}
	}

}
