/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.liveSense.service.apacheds;

import org.apache.directory.server.core.api.DirectoryService;
import org.apache.directory.server.ldap.LdapServer;
import org.apache.directory.server.protocol.shared.transport.TcpTransport;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class EmbeddedLdapServerParameterProvider {
	public static final String PROP_PORT_NAME = "ldap.service.name";
	public static final int DEFAULT_PORT = 10389;

	public static Integer getPort(ComponentContext context) {
		return PropertiesUtil.toInteger(context!=null?context.getProperties().get(PROP_PORT_NAME):DEFAULT_PORT, DEFAULT_PORT);
	}
}

@Component(label="%ldap.service.name", description="%ldap.service.description", immediate=true, metatype=true)
@Properties(value={
		@Property(name=EmbeddedLdapServerParameterProvider.PROP_PORT_NAME, intValue=EmbeddedLdapServerParameterProvider.DEFAULT_PORT)
})
public class EmbeddedLdapServerActivator {

	Logger log = LoggerFactory.getLogger(EmbeddedLdapServerActivator.class);
	
	/** The directory service */
	@Reference(policy=ReferencePolicy.DYNAMIC, cardinality=ReferenceCardinality.MANDATORY_UNARY)
	private DirectoryService service;

	/** The LDAP server */
	private LdapServer ldapServer = null;
	private ServiceRegistration ldapServerReg = null;

	@Activate
	protected void activate(ComponentContext context) {
		try {
			startLdapServer(EmbeddedLdapServerParameterProvider.getPort(context));
			
			// Register as LDAP server
			ldapServerReg = context.getBundleContext().registerService(LdapServer.class.getName(), ldapServer, null);
			
		} catch (Exception e) {
			log.error("Could not activate LDAP server", e);
		}
	}

	@Deactivate
	protected void deactivate(ComponentContext context) {
		if (ldapServerReg != null)
			context.getBundleContext().ungetService(ldapServerReg.getReference());
		stopLdapServer();
	}

	/**
	 * starts the LdapServer
	 * 
	 * @throws Exception
	 */
	public void startLdapServer(int serverPort) throws Exception {
		if (serverPort > 0) {
			ldapServer = new LdapServer();
			ldapServer.setTransports(new TcpTransport(serverPort));
			ldapServer.setDirectoryService(service);
			ldapServer.start();
		}
	}

	public void stopLdapServer() {
		if (ldapServer != null)
			ldapServer.stop();
	}

}