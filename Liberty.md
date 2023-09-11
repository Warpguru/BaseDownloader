# Liberty configuration for BaseDownloader



## bootstrap.properties

```
# Define HTTP ports
default.http.port=9080
default.https.port=9443
# Define JMS ports (defaults 7276, 7286)
default.jms.port=7276
default.jmss.port=7286
# Define JMS ports of STS
sts.jms.port=7476
sts.jmss.port=7486
# Define IIOP ports (defaults 2809)
default.iiop.port=2809
 
# Overwriting property in liberty-maven-plugin
#project.basedir=D:/Workspace/Eclipse/sisii/trunk/BatchJobs/BatchServer
#project.build.directory=D:/Workspace/Eclipse/sisii/trunk/BatchJobs/BatchServer/target
logback-file=logback.xml
 
# Define logging (alternative to server.xml) (see: https://www.ibm.com/support/knowledgecenter/SSEQTP_liberty/com.ibm.websphere.wlp.doc/ae/rwlp_logging.html)
#com.ibm.ws.logging.trace.file.name="stdout" (logging to "stdout" prevents server from starting)
#com.ibm.ws.logging.trace.specification="*=INFO:openjpa.jdbc=all"
#com.ibm.ws.logging.trace.append="true" (does not work)
#com.ibm.ws.logging.copy.system.streams="true"
```

## jvm.options

```
-Ddefault.client.encoding=UTF-8
-Duser.country=US
-Duser.language=en
-Dxjavax.net.debug=ssl
-Dxjavax.net.debug=all
-Xms512m
-Xmx2048m
# Enable verbose output for class loading.
#-verbose:class

# Remote debug configuration
#-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5008

# Glowroot APM (v0.12.0 works for JDK8)
#-javaagent:D:/Development/Glowroot/0.12.0/glowroot.jar

# HTTP debug options
#-Dcom.sun.xml.internal.ws.transport.http.HttpAdapter.dumpTreshold=true
#-Dcom.sun.xml.internal.ws.transport.http.HttpAdapter.dump=true
#-Dcom.sun.xml.ws.transport.http.HttpAdapter.dump=true
#-Dcom.sun.xml.internal.ws.transport.http.client.HttpTransportPipe.dump=true
#-Dcom.sun.xml.ws.transport.http.client.HttpTransportPipe.dump=true
```

## server.env

```
# WLP specific settings
keystore_password=WDBeWH4JUa85sWq00Twk6oG
WLP_SKIP_MAXPERMSIZE=true
WLP_KEYSTORE_PASSWORD=liberty
WLP_ADMIN_USERID=admin
WLP_ADMIN_PASSWORD=adminpwd
# Application specific settings
#CONFIGURATION_PATH=${wlp.install.dir}SBS
#logback-file=logback.xml
```

## server.xml

```
<?xml version="1.0" encoding="UTF-8"?>
<server description="BaseDownloader Server">

    <!-- Enable features -->
    <featureManager>
        <feature>localConnector-1.0</feature>
    	<feature>adminCenter-1.0</feature>
		<feature>openapi-3.1</feature>
		<feature>jakartaee-8.0</feature>
	    <feature>restConnector-2.0</feature>
    </featureManager>

    <!-- To access this server from a remote client add a host attribute to the following element, e.g. host="*" -->
	<httpEndpoint host="*" httpPort="${default.http.port}" httpsPort="${default.https.port}" id="defaultHttpEndpoint">
	        <accessLogging enabled="true" logFormat="%h %i %u %t &quot;%r&quot; %s %b %D"/>
	</httpEndpoint>
	
	<!-- ORB IIOP settings --> 
	<!-- 
	<iiopEndpoint host="localhost" id="defaultIiopEndpoint" iiopPort="${default.iiop.port}"/> 
	-->
	
	<!-- Message JMS Server settings -->
	<wasJmsEndpoint wasJmsPort="${default.jms.port}" wasJmsSSLPort="${default.jmss.port}"/>

	<!-- Default SSL configuration enables trust for default certificates from the Java runtime -->
	<ssl id="defaultSSLConfig" trustDefaultCerts="true"/>
	 
	<keyStore id="defaultKeyStore" password="${WLP_KEYSTORE_PASSWORD}"/>
	 
	<!-- Define  users -->
	<basicRegistry id="basic">
		<user name="${WLP_ADMIN_USERID}" password="${WLP_ADMIN_PASSWORD}"/>
	</basicRegistry>
	 
	<!-- A user with the administrator-role has full access to the Admin Center -->
	<administrator-role>
		<user>${WLP_ADMIN_USERID}</user>
	</administrator-role>
	 
	<!-- Allow AdminCenter to write configuration -->
	<remoteFileAccess>
		<writeDir>${server.config.dir}</writeDir>
	</remoteFileAccess>
	 
	<!-- Automatically expand WAR files and EAR files -->
	<applicationManager autoExpand="true"/>
	 
	<applicationMonitor updateTrigger="mbean"/>
	 
	<mpMetrics authentication="false"/>

	<logging maxFileSize="20" maxFiles="10" traceFileName="trace.log" traceFormat="BASIC" traceSpecification="eclipselink.sql=all"/>
    

    <webApplication contextRoot="base-downloader" id="BaseDownloader" location="base-downloader.war" name="BaseDownloader"/>
</server>
```
