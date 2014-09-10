package jetbrains.buildServer.clouds.azure.connector;

import com.intellij.openapi.diagnostic.Logger;
import com.microsoft.windowsazure.Configuration;
import com.microsoft.windowsazure.core.OperationStatusResponse;
import com.microsoft.windowsazure.core.utils.Base64;
import com.microsoft.windowsazure.core.utils.KeyStoreType;
import com.microsoft.windowsazure.exception.ServiceException;
import com.microsoft.windowsazure.management.compute.*;
import com.microsoft.windowsazure.management.compute.models.*;
import com.microsoft.windowsazure.management.configuration.ManagementConfiguration;
import java.io.*;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyStore;
import java.security.Security;
import java.util.*;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import jetbrains.buildServer.clouds.InstanceStatus;
import jetbrains.buildServer.clouds.azure.AzureCloudImage;
import jetbrains.buildServer.clouds.azure.AzureCloudImageDetails;
import jetbrains.buildServer.clouds.azure.AzureCloudInstance;
import jetbrains.buildServer.clouds.azure.AzurePropertiesNames;
import jetbrains.buildServer.clouds.base.connector.CloudApiConnector;
import jetbrains.buildServer.clouds.base.errors.TypedCloudErrorInfo;
import org.apache.log4j.Priority;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jetbrains.annotations.NotNull;
import org.xml.sax.SAXException;

/**
 * @author Sergey.Pak
 *         Date: 8/5/2014
 *         Time: 2:13 PM
 */
public class AzureApiConnector implements CloudApiConnector<AzureCloudImage, AzureCloudInstance> {

  private static final Logger LOG = Logger.getInstance(AzureApiConnector.class.getName());
  private static final int MIN_PORT_NUMBER = 9092;

  private final ConcurrentMap<String, Lock> myServiceLocks = new ConcurrentHashMap<String, Lock>();
  private final String myUrl;
  private final String myManagementCertificate;
  private final String mySubscriptionId;
  private final Configuration myConfiguration;
  private final ComputeManagementClient myClient;

  public AzureApiConnector(@NotNull final String url, @NotNull final String managementCertificate, @NotNull final String subscriptionId) {
    myUrl = url;
    myManagementCertificate = managementCertificate;
    mySubscriptionId = subscriptionId;
    ClassLoader old = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());
      myConfiguration = prepareConfiguration();
      myClient = ComputeManagementService.create(myConfiguration);
    } finally {
      Thread.currentThread().setContextClassLoader(old);
    }
  }

  public InstanceStatus getInstanceStatus(@NotNull final AzureCloudInstance instance) {
    final Map<String, AzureInstance> instanceMap = listImageInstances(instance.getImage());
    final AzureInstance instanceData = instanceMap.get(instance.getInstanceId());
    if (instanceData != null) {
      return instanceData.getInstanceStatus();
    } else {
      return InstanceStatus.UNKNOWN;
    }
  }

  public Map<String, AzureInstance> listImageInstances(@NotNull final AzureCloudImage image) {
    try {
      final DeploymentOperations ops = myClient.getDeploymentsOperations();
      final AzureCloudImageDetails imageDetails = image.getImageDetails();
      final DeploymentGetResponse deploymentResponse = ops.getByName(imageDetails.getServiceName(), imageDetails.getDeploymentName());
      Map<String, AzureInstance> retval = new HashMap<String, AzureInstance>();
      for (RoleInstance instance : deploymentResponse.getRoleInstances()) {
        retval.put(instance.getInstanceName(), new AzureInstance(instance));
      }
      return retval;
    } catch (Exception e) {
      LOG.warn(e.toString());
      return Collections.emptyMap();
    }
  }

  public Collection<TypedCloudErrorInfo> checkImage(@NotNull final AzureCloudImage image) {
    return Collections.emptyList();
  }

  public Collection<TypedCloudErrorInfo> checkInstance(@NotNull final AzureCloudInstance instance) {
    return Collections.emptyList();
  }

  public OperationStatusResponse createAndStartVM(@NotNull final AzureCloudImage image, @NotNull final String vmName, final boolean generalized)
    throws SAXException, InterruptedException, ExecutionException, TransformerException, ServiceException, URISyntaxException, ParserConfigurationException, IOException {
    final AzureCloudImageDetails imageDetails = image.getImageDetails();
    final HostedServiceOperations servicesOperations = myClient.getHostedServicesOperations();
    final HostedServiceGetDetailedResponse service = servicesOperations.getDetailed(imageDetails.getServiceName());
    int portNumber = MIN_PORT_NUMBER;
    for (HostedServiceGetDetailedResponse.Deployment deployment : service.getDeployments()) {
      for (RoleInstance instance : deployment.getRoleInstances()) {
        for (InstanceEndpoint endpoint : instance.getInstanceEndpoints()) {
          if (AzurePropertiesNames.ENDPOINT_NAME.equals(endpoint.getName()) && endpoint.getPort() >= portNumber) {
            portNumber = endpoint.getPort() + 1;
          }
        }
      }
    }
    final VirtualMachineOperations vmOperations = myClient.getVirtualMachinesOperations();

    final VirtualMachineCreateParameters parameters = new VirtualMachineCreateParameters();
    parameters.setRoleSize(imageDetails.getVmSize());
    parameters.setProvisionGuestAgent(Boolean.TRUE);
    parameters.setRoleName(vmName);
    parameters.setVMImageName(image.getName());
    final ArrayList<ConfigurationSet> configurationSetList = createConfigurationSetList(portNumber);
    parameters.setConfigurationSets(configurationSetList);
    if (generalized){
      ConfigurationSet provisionConf = new ConfigurationSet();
      configurationSetList.add(provisionConf);
      if ("Linux".equals(imageDetails.getOsType())){
        provisionConf.setConfigurationSetType(ConfigurationSetTypes.LINUXPROVISIONINGCONFIGURATION);
        provisionConf.setHostName(vmName);
        provisionConf.setUserName(imageDetails.getUsername());
        provisionConf.setUserPassword(imageDetails.getPassword());
      } else {
        provisionConf.setConfigurationSetType(ConfigurationSetTypes.WINDOWSPROVISIONINGCONFIGURATION);
        provisionConf.setComputerName(vmName);
        provisionConf.setAdminUserName(imageDetails.getUsername());
        provisionConf.setAdminPassword(imageDetails.getPassword());
      }
    }
    try {
      waitAndGetLock(imageDetails.getServiceName());
      final OperationStatusResponse response = vmOperations.create(imageDetails.getServiceName(), imageDetails.getDeploymentName(), parameters);
      return response;
    } finally {
      unlock(imageDetails.getServiceName());
    }
  }

  public OperationStatusResponse stopVM(@NotNull final AzureCloudInstance instance)
    throws InterruptedException, ExecutionException, ServiceException, IOException {
    final VirtualMachineOperations vmOperations = myClient.getVirtualMachinesOperations();
    final AzureCloudImageDetails imageDetails = instance.getImage().getImageDetails();
    final VirtualMachineShutdownParameters shutdownParams = new VirtualMachineShutdownParameters();
    shutdownParams.setPostShutdownAction(PostShutdownAction.StoppedDeallocated);
    return vmOperations.shutdown(imageDetails.getServiceName(), imageDetails.getDeploymentName(), instance.getName(), shutdownParams);
  }

  public Future<OperationStatusResponse> deleteVM(@NotNull final AzureCloudInstance instance) {
    final VirtualMachineOperations vmOperations = myClient.getVirtualMachinesOperations();
    final AzureCloudImageDetails imageDetails = instance.getImage().getImageDetails();
    return vmOperations.deleteAsync(imageDetails.getServiceName(), imageDetails.getDeploymentName(), instance.getName(), true);
  }

  public Future<OperationStatusResponse> startVM(@NotNull final AzureCloudImage image, @NotNull final String instanceName) {
    final VirtualMachineOperations vmOperations = myClient.getVirtualMachinesOperations();
    final AzureCloudImageDetails imageDetails = image.getImageDetails();

    return vmOperations.startAsync(imageDetails.getServiceName(), imageDetails.getDeploymentName(), instanceName);
  }

  public OperationStatusResponse getOperationStatus(@NotNull final String operationId) throws ServiceException, ParserConfigurationException, SAXException, IOException {
    return myClient.getOperationStatus(operationId);
  }

  public boolean isServiceFree(@NotNull final String serviceName){
    final Lock lock = myServiceLocks.get(serviceName);
    if (lock == null) {
      return true;
    }
    final boolean tryLock = lock.tryLock();
    if (tryLock) {
      lock.unlock();
    }
    return tryLock;
  }

  public boolean isImageGeneralized(@NotNull final String imageName) {
    final VirtualMachineVMImageOperations vmImagesOperations = myClient.getVirtualMachineVMImagesOperations();
    try {
      for (VirtualMachineVMImageListResponse.VirtualMachineVMImage image : vmImagesOperations.list()) {
        if (imageName.equals(image.getName())){
          return "Generalized".equals(image.getOSDiskConfiguration().getOSState());
        }
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    throw new RuntimeException("Unable to find image with name " + imageName);
  }

  private Configuration prepareConfiguration() throws RuntimeException {
    try {
      final File tempFile = File.createTempFile("azk", null);
      FileOutputStream fOut = new FileOutputStream(tempFile);
      Random r = new Random();
      byte[] pwdData = new byte[4];
      r.nextBytes(pwdData);
      final String base64pw = Base64.encode(pwdData).substring(0, 6);
      final KeyStore keyStorePKCS12 = createKeyStorePKCS12(myManagementCertificate, fOut, base64pw);
      return ManagementConfiguration.configure(new URI(myUrl), mySubscriptionId, tempFile.getPath(), base64pw, KeyStoreType.pkcs12);
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
  }

  private static KeyStore createKeyStorePKCS12(String base64Certificate, OutputStream keyStoreOutputStream, String keystorePwd) throws Exception {
    Security.addProvider(new BouncyCastleProvider());
    KeyStore store = KeyStore.getInstance("PKCS12", BouncyCastleProvider.PROVIDER_NAME);
    store.load(null, null);

    // read in the value of the base 64 cert without a password (PBE can be applied afterwards if this is needed
    final byte[] decode = Base64.decode(base64Certificate);
    InputStream sslInputStream = new ByteArrayInputStream(decode);
    store.load(sslInputStream, "".toCharArray());

    // we need to a create a physical keystore as well here
    store.store(keyStoreOutputStream, keystorePwd.toCharArray());
    keyStoreOutputStream.close();
    return store;
  }


  private void waitAndGetLock(@NotNull final String serviceName) {
    myServiceLocks.putIfAbsent(serviceName, new ReentrantLock(true));
    myServiceLocks.get(serviceName).lock();
  }

  private void unlock(@NotNull final String serviceName) {
    myServiceLocks.get(serviceName).unlock();
  }

  private static ArrayList<ConfigurationSet> createConfigurationSetList(int port) {
    ArrayList<ConfigurationSet> retval = new ArrayList<ConfigurationSet>();
    final ConfigurationSet value = new ConfigurationSet();
    value.setConfigurationSetType(ConfigurationSetTypes.NETWORKCONFIGURATION);
    final ArrayList<InputEndpoint> endpointsList = new ArrayList<InputEndpoint>();
    value.setInputEndpoints(endpointsList);
    InputEndpoint endpoint = new InputEndpoint();
    endpointsList.add(endpoint);
    endpoint.setLocalPort(port);
    endpoint.setPort(port);
    endpoint.setProtocol("TCP");
    endpoint.setName("TC Agent");
    retval.add(value);
    return retval;
  }
}