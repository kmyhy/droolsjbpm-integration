/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.kie.server.services.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.naming.InitialContext;

import org.drools.compiler.kie.builder.impl.InternalKieContainer;
import org.drools.compiler.kie.builder.impl.InternalKieScanner;
import org.drools.compiler.kproject.xml.DependencyFilter;
import org.kie.api.KieServices;
import org.kie.api.builder.Message.Level;
import org.kie.api.builder.Results;
import org.kie.scanner.KieModuleMetaData;
import org.kie.server.api.KieServerConstants;
import org.kie.server.api.KieServerEnvironment;
import org.kie.server.api.Version;
import org.kie.server.api.model.KieContainerResource;
import org.kie.server.api.model.KieContainerResourceList;
import org.kie.server.api.model.KieContainerStatus;
import org.kie.server.api.model.KieScannerResource;
import org.kie.server.api.model.KieScannerStatus;
import org.kie.server.api.model.KieServerInfo;
import org.kie.server.api.model.KieServerStateInfo;
import org.kie.server.api.model.Message;
import org.kie.server.api.model.ReleaseId;
import org.kie.server.api.model.ServiceResponse;
import org.kie.server.api.model.ServiceResponse.ResponseType;
import org.kie.server.api.model.Severity;
import org.kie.server.controller.api.KieServerController;
import org.kie.server.controller.api.model.KieServerSetup;
import org.kie.server.services.api.KieControllerNotConnectedException;
import org.kie.server.services.api.KieControllerNotDefinedException;
import org.kie.server.services.api.KieServerExtension;
import org.kie.server.services.api.KieServerRegistry;
import org.kie.server.services.impl.controller.ControllerConnectRunnable;
import org.kie.server.services.impl.controller.DefaultRestControllerImpl;
import org.kie.server.services.impl.security.JACCIdentityProvider;
import org.kie.server.services.impl.storage.KieServerState;
import org.kie.server.services.impl.storage.KieServerStateRepository;
import org.kie.server.services.impl.storage.file.KieServerStateFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KieServerImpl {

    private static final Logger             logger               = LoggerFactory.getLogger(KieServerImpl.class);

    private static final ServiceLoader<KieServerExtension> serverExtensions = ServiceLoader.load(KieServerExtension.class);

    private static final ServiceLoader<KieServerController> kieControllers = ServiceLoader.load(KieServerController.class);
    // TODO figure out how to get actual URL of the kie server
    private String kieServerLocation = System.getProperty(KieServerConstants.KIE_SERVER_LOCATION, "http://localhost:8230/kie-server/services/rest/server");

    private final KieServerRegistry context;
    private final ContainerManager containerManager;

    private final KieServerStateRepository repository;
    private volatile AtomicBoolean kieServerActive = new AtomicBoolean(false);

    private List<Message> serverMessages = new ArrayList<Message>();
    private Map<String, List<Message>> containerMessages = new ConcurrentHashMap<String, List<Message>>();

    public KieServerImpl() {
        this.repository = new KieServerStateFileRepository();

        this.context = new KieServerRegistryImpl();
        this.context.registerIdentityProvider(new JACCIdentityProvider());
        this.context.registerStateRepository(repository);

        this.containerManager = getContainerManager();

        KieServerState currentState = repository.load(KieServerEnvironment.getServerId());

        List<KieServerExtension> extensions = sortKnownExtensions();

        for (KieServerExtension extension : extensions) {
            if (!extension.isActive()) {
                continue;
            }
            try {
                extension.init(this, this.context);

                this.context.registerServerExtension(extension);

                logger.info("{} has been successfully registered as server extension", extension);
            } catch (Exception e) {
                serverMessages.add(new Message(Severity.ERROR, "Error when initializing server extension of type " + extension + " due to " + e.getMessage()));
                logger.error("Error when initializing server extension of type {}", extension, e);
            }
        }
        kieServerActive.set(true);
        boolean readyToRun = false;
        KieServerController kieController = getController();
        // try to load container information from available controllers if any...
        KieServerInfo kieServerInfo = getInfoInternal();
        Set<KieContainerResource> containers = null;
        KieServerSetup kieServerSetup = null;
        try {
            kieServerSetup = kieController.connect(kieServerInfo);

            containers = kieServerSetup.getContainers();
            readyToRun = true;
        } catch (KieControllerNotDefinedException e) {
            // if no controllers use local storage
            containers = currentState.getContainers();
            kieServerSetup = new KieServerSetup();
            readyToRun = true;
        } catch (KieControllerNotConnectedException e) {
            // if controllers are defined but cannot be reached schedule connection and disable until it gets connection to one of them
            readyToRun = false;
            logger.warn("Unable to connect to any controllers, delaying container installation until connection can be established");
            Thread connectToControllerThread = new Thread(new ControllerConnectRunnable(kieServerActive,
                                                                                        kieController,
                                                                                        kieServerInfo,
                                                                                        currentState,
                                                                                        containerManager,
                                                                                        this), "KieServer-ControllerConnect");
            connectToControllerThread.start();
            if (Boolean.parseBoolean(currentState.getConfiguration().getConfigItemValue(KieServerConstants.CFG_SYNC_DEPLOYMENT, "false"))) {
                logger.info("Containers were requested to be deployed synchronously, holding application start...");
                try {
                    connectToControllerThread.join();
                } catch (InterruptedException e1) {
                    logger.debug("Interrupt exception when waiting for deployments");
                }
            }
        }

        if (readyToRun) {
            addServerStatusMessage(kieServerInfo);
            containerManager.installContainers(this, containers, currentState, kieServerSetup);
        }
    }



    public KieServerRegistry getServerRegistry() { 
        return context;
    }


    public void destroy() {
        kieServerActive.set(false);
        // disconnect from controller
        KieServerController kieController = getController();
        kieController.disconnect(getInfoInternal());

        for (KieServerExtension extension : context.getServerExtensions()) {

            try {
                extension.destroy(this, this.context);

                this.context.unregisterServerExtension(extension);

                logger.info("{} has been successfully unregistered as server extension", extension);
            } catch (Exception e) {
                logger.error("Error when destroying server extension of type {}", extension, e);
            }
        }

    }


    public List<KieServerExtension> getServerExtensions() {
        return this.context.getServerExtensions();
    }

    protected KieServerInfo getInfoInternal() {
        Version version = KieServerEnvironment.getVersion();
        String serverId = KieServerEnvironment.getServerId();
        String serverName = KieServerEnvironment.getServerName();
        String versionStr = version != null ? version.toString() : "Unknown-Version";

        List<String> capabilities = new ArrayList<String>();
        for (KieServerExtension extension : context.getServerExtensions()) {
            capabilities.add(extension.getImplementedCapability());
        }

        return new KieServerInfo(serverId, serverName, versionStr, capabilities, kieServerLocation);

    }

    public ServiceResponse<KieServerInfo> getInfo() {
        try {
            KieServerInfo kieServerInfo = getInfoInternal();
            kieServerInfo.setMessages(serverMessages);

            return new ServiceResponse<KieServerInfo>(ServiceResponse.ResponseType.SUCCESS, "Kie Server info", kieServerInfo);
        } catch (Exception e) {
            logger.error("Error retrieving server info:", e);
            return new ServiceResponse<KieServerInfo>(ServiceResponse.ResponseType.FAILURE, "Error retrieving kie server info: " +
                    e.getClass().getName() + ": " + e.getMessage());
        }
    }

    public ServiceResponse<KieContainerResource> createContainer(String containerId, KieContainerResource container) {
        if (container == null || container.getReleaseId() == null) {
            logger.error("Error creating container. Release Id is null: " + container);
            return new ServiceResponse<KieContainerResource>(ServiceResponse.ResponseType.FAILURE, "Failed to create container " + containerId + ". Release Id is null: " + container + ".");
        }
        List<Message> messages = new CopyOnWriteArrayList<Message>();

        container.setContainerId(containerId);
        ReleaseId releaseId = container.getReleaseId();
        try {
            KieContainerInstanceImpl ci = new KieContainerInstanceImpl(containerId, KieContainerStatus.CREATING);
            KieContainerInstanceImpl previous = null;
            // have to synchronize on the ci or a concurrent call to dispose may create inconsistencies
            synchronized (ci) {
                previous = context.registerContainer(containerId, ci);
                if (previous == null) {
                    try {
                        KieServices ks = KieServices.Factory.get();
                        InternalKieContainer kieContainer = (InternalKieContainer) ks.newKieContainer(releaseId);
                        if (kieContainer != null) {
                            ci.setKieContainer(kieContainer);
                            logger.debug("Container {} (for release id {}) general initialization: DONE", containerId, releaseId);

                            KieModuleMetaData metaData = KieModuleMetaData.Factory.newKieModuleMetaData(releaseId, DependencyFilter.COMPILE_FILTER);
                            Map<String, Object> parameters = new HashMap<String, Object>();
                            parameters.put(KieServerConstants.KIE_SERVER_PARAM_MODULE_METADATA, metaData);
                            // process server extensions
                            List<KieServerExtension> extensions = context.getServerExtensions();
                            for (KieServerExtension extension : extensions) {
                                extension.createContainer(containerId, ci, parameters);
                                logger.debug("Container {} (for release id {}) {} initialization: DONE", containerId, releaseId, extension);
                            }

                            ci.getResource().setStatus(KieContainerStatus.STARTED);
                            logger.info("Container {} (for release id {}) successfully started", containerId, releaseId);


                            // store the current state of the server
                            KieServerState currentState = repository.load(KieServerEnvironment.getServerId());
                            container.setStatus(KieContainerStatus.STARTED);
                            currentState.getContainers().add(container);

                            repository.store(KieServerEnvironment.getServerId(), currentState);
                            messages.add(new Message(Severity.INFO, "Container " + containerId + " successfully created with module " + releaseId + "."));

                            return new ServiceResponse<KieContainerResource>(ServiceResponse.ResponseType.SUCCESS, "Container " + containerId + " successfully deployed with module " + releaseId + ".", ci.getResource());
                        } else {
                            messages.add(new Message(Severity.ERROR, "KieContainer could not be found for release id " + releaseId));
                            ci.getResource().setStatus(KieContainerStatus.FAILED);
                            ci.getResource().setReleaseId(releaseId);
                            return new ServiceResponse<KieContainerResource>(ServiceResponse.ResponseType.FAILURE, "Failed to create container " + containerId + " with module " + releaseId + ".");
                        }
                    } catch (Exception e) {
                        messages.add(new Message(Severity.ERROR, "Error creating container '" + containerId + "' for module '" + releaseId + "' due to " + e.getMessage()));
                        logger.error("Error creating container '" + containerId + "' for module '" + releaseId + "'", e);
                        ci.getResource().setStatus(KieContainerStatus.FAILED);
                        ci.getResource().setReleaseId(releaseId);
                        return new ServiceResponse<KieContainerResource>(ServiceResponse.ResponseType.FAILURE, "Failed to create container " + containerId + " with module " + releaseId + ": " + e.getClass().getName() + ": " + e.getMessage());
                    }
                } else {
                    messages.add(new Message(Severity.ERROR, "Container " + containerId + " already exists."));
                    return new ServiceResponse<KieContainerResource>(ServiceResponse.ResponseType.FAILURE, "Container " + containerId + " already exists.", previous.getResource());
                }
            }
        } catch (Exception e) {
            messages.add(new Message(Severity.ERROR, "Error creating container '" + containerId + "' for module '" + releaseId + "' due to " + e.getMessage()));
            logger.error("Error creating container '" + containerId + "' for module '" + releaseId + "'", e);
            return new ServiceResponse<KieContainerResource>(ServiceResponse.ResponseType.FAILURE, "Error creating container " + containerId +
                    " with module " + releaseId + ": " + e.getClass().getName() + ": " + e.getMessage());
        } finally {
            this.containerMessages.put(containerId, messages);
        }

    }

    public ServiceResponse<KieContainerResourceList> listContainers() {
        try {
            List<KieContainerResource> containers = new ArrayList<KieContainerResource>();
            for (KieContainerInstanceImpl instance : context.getContainers()) {
                instance.getResource().setMessages(getMessagesForContainer(instance.getContainerId()));
                containers.add(instance.getResource());
            }
            KieContainerResourceList cil = new KieContainerResourceList(containers);
            return new ServiceResponse<KieContainerResourceList>(ServiceResponse.ResponseType.SUCCESS, "List of created containers", cil);
        } catch (Exception e) {
            logger.error("Error retrieving list of containers", e);
            return new ServiceResponse<KieContainerResourceList>(ServiceResponse.ResponseType.FAILURE, "Error listing containers: " +
                    e.getClass().getName() + ": " + e.getMessage());
        }
    }

    public ServiceResponse<KieContainerResource> getContainerInfo(String id) {
        try {
            KieContainerInstanceImpl ci = context.getContainer(id);
            if (ci != null) {
                if( ci.getResource().getScanner() == null ) {
                    ci.getResource().setScanner( getScannerResource( ci ) );
                }
                ci.getResource().setMessages(getMessagesForContainer(id));
                return new ServiceResponse<KieContainerResource>(ServiceResponse.ResponseType.SUCCESS, "Info for container " + id, ci.getResource());
            }
            return new ServiceResponse<KieContainerResource>(ServiceResponse.ResponseType.FAILURE, "Container " + id + " is not instantiated.");
        } catch (Exception e) {
            logger.error("Error retrieving info for container '" + id + "'", e);
            return new ServiceResponse<KieContainerResource>(ServiceResponse.ResponseType.FAILURE, "Error retrieving container info: " +
                    e.getClass().getName() + ": " + e.getMessage());
        }
    }

    public ServiceResponse<Void> disposeContainer(String containerId) {
        List<Message> messages = new CopyOnWriteArrayList<Message>();
        try {
            KieContainerInstanceImpl kci = (KieContainerInstanceImpl) context.unregisterContainer(containerId);
            if (kci != null) {
                synchronized (kci) {
                    kci.setStatus(KieContainerStatus.DISPOSING); // just in case
                    if (kci.getKieContainer() != null) {
                        List<KieServerExtension> disposedExtensions = new ArrayList<KieServerExtension>();
                        try {
                            // first attempt to dispose container on all extensions
                            logger.debug("Container {} (for release id {}) shutdown: In Progress", containerId, kci.getResource().getReleaseId());
                            // process server extensions
                            List<KieServerExtension> extensions = context.getServerExtensions();
                            for (KieServerExtension extension : extensions) {
                                extension.disposeContainer(containerId, kci, new HashMap<String, Object>());
                                logger.debug("Container {} (for release id {}) {} shutdown: DONE", containerId, kci.getResource().getReleaseId(), extension);
                                disposedExtensions.add(extension);
                            }

                        } catch (Exception e) {
                            logger.warn("Dispose of container {} failed, putting it back to started state by recreating container on {}", containerId, disposedExtensions);
                            // since the dispose fail rollback must take place to put it back to running state
                            for (KieServerExtension extension : disposedExtensions) {
                                extension.createContainer(containerId, kci, new HashMap<String, Object>());
                                logger.debug("Container {} (for release id {}) {} restart: DONE", containerId, kci.getResource().getReleaseId(), extension);
                            }

                            kci.setStatus(KieContainerStatus.STARTED);
                            context.registerContainer(containerId, kci);
                            logger.info("Container {} (for release id {}) STARTED after failed dispose", containerId, kci.getResource().getReleaseId());

                            messages.add(new Message(Severity.WARN, "Error disposing container '" + containerId + "' due to " + e.getMessage() + ", container is running"));

                            return new ServiceResponse<Void>(ResponseType.FAILURE, "Container " + containerId +
                                    " failed to dispose, exception was raised: " + e.getClass().getName() + ": " + e.getMessage());
                        }
                        InternalKieContainer kieContainer = kci.getKieContainer();
                        kci.setKieContainer(null); // helps reduce concurrent access issues
                        // this may fail, but we already removed the container from the registry
                        kieContainer.dispose();
                        logger.info("Container {} (for release id {}) successfully stopped", containerId, kci.getResource().getReleaseId());

                        // store the current state of the server
                        KieServerState currentState = repository.load(KieServerEnvironment.getServerId());

                        List<KieContainerResource> containers = new ArrayList<KieContainerResource>();
                        for (KieContainerResource containerResource : currentState.getContainers()) {
                            if ( !containerId.equals(containerResource.getContainerId()) ) {
                                containers.add(containerResource);
                            }
                        }
                        currentState.setContainers(new HashSet<KieContainerResource>(containers));

                        repository.store(KieServerEnvironment.getServerId(), currentState);
                        messages.add(new Message(Severity.INFO, "Container " + containerId + " successfully stopped."));

                        return new ServiceResponse<Void>(ServiceResponse.ResponseType.SUCCESS, "Container " + containerId + " successfully disposed.");
                    } else {
                        messages.add(new Message(Severity.INFO, "Container " + containerId + " was not instantiated."));

                        return new ServiceResponse<Void>(ServiceResponse.ResponseType.SUCCESS, "Container " + containerId + " was not instantiated.");
                    }
                }
            } else {
                messages.add(new Message(Severity.INFO, "Container " + containerId + " was not instantiated."));

                return new ServiceResponse<Void>(ServiceResponse.ResponseType.SUCCESS, "Container " + containerId + " was not instantiated.");
            }
        } catch (Exception e) {
            messages.add(new Message(Severity.ERROR, "Error disposing container '" + containerId + "' due to " + e.getMessage()));
            logger.error("Error disposing Container '" + containerId + "'", e);
            return new ServiceResponse<Void>(ServiceResponse.ResponseType.FAILURE, "Error disposing container " + containerId + ": " +
                    e.getClass().getName() + ": " + e.getMessage());
        } finally {
            this.containerMessages.put(containerId, messages);
        }
    }

    public ServiceResponse<KieScannerResource> getScannerInfo(String id) {
        try {
            KieContainerInstanceImpl kci = context.getContainer(id);
            if (kci != null && kci.getKieContainer() != null) {
                KieScannerResource info = getScannerResource( kci );
                kci.getResource().setScanner( info );
                return new ServiceResponse<KieScannerResource>(ServiceResponse.ResponseType.SUCCESS, "Scanner info successfully retrieved", info);
            } else {
                return new ServiceResponse<KieScannerResource>(ServiceResponse.ResponseType.FAILURE,
                        "Unknown container " + id + ".");
            }
        } catch (Exception e) {
            logger.error("Error retrieving scanner info for container '" + id + "'.", e);
            return new ServiceResponse<KieScannerResource>(ServiceResponse.ResponseType.FAILURE, "Error retrieving scanner info for container '" + id + "': " +
                    e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private KieScannerResource getScannerResource(KieContainerInstanceImpl kci) {
        InternalKieScanner scanner = kci.getScanner();
        KieScannerResource info = null;
        if (scanner != null) {
            info = new KieScannerResource(mapStatus(scanner.getStatus()), scanner.getPollingInterval());
        } else {
            info = new KieScannerResource( KieScannerStatus.DISPOSED);
        }
        return info;
    }

    public ServiceResponse<KieScannerResource> updateScanner(String id, KieScannerResource resource) {
        if (resource == null || resource.getStatus() == null) {
            logger.error("Error updating scanner for container " + id + ". Status is null: " + resource);
            return new ServiceResponse<KieScannerResource>(ServiceResponse.ResponseType.FAILURE, "Error updating scanner for container " + id + ". Status is null: " + resource);
        }
        KieScannerStatus status = resource.getStatus();
        try {
            KieContainerInstanceImpl kci = context.getContainer(id);
            if (kci != null && kci.getKieContainer() != null) {
                ServiceResponse<KieScannerResource> result = null;
                switch (status) {
                    case CREATED:
                        // create the scanner
                        result = createScanner(id, kci);
                        break;
                    case STARTED:
                        // start the scanner
                        result = startScanner(id, resource, kci);
                        break;
                    case STOPPED:
                        // stop the scanner
                        result = stopScanner(id, resource, kci);
                        break;
                    case SCANNING:
                        // scan now
                        result = scanNow(id, resource, kci);
                        break;
                    case DISPOSED:
                        // dispose
                        result = disposeScanner(id, resource, kci);
                        break;
                    default:
                        // error
                        result = new ServiceResponse<KieScannerResource>(ServiceResponse.ResponseType.FAILURE,
                                "Unknown status '" + status + "' for scanner on container " + id + ".");
                        break;
                }
                kci.getResource().setScanner( result.getResult() ); // might be null, but that is ok
                return result;
            } else {
                return new ServiceResponse<KieScannerResource>(ServiceResponse.ResponseType.FAILURE,
                        "Unknown container " + id + ".");
            }
        } catch (Exception e) {
            logger.error("Error updating scanner for container '" + id + "': " + resource, e);
            return new ServiceResponse<KieScannerResource>(ServiceResponse.ResponseType.FAILURE, "Error updating scanner for container '" + id +
                    "': " + resource + ": " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private ServiceResponse<KieScannerResource> startScanner(String id, KieScannerResource resource, KieContainerInstanceImpl kci) {
        List<Message> messages = getMessagesForContainer(id);
        messages.clear();
        if (kci.getScanner() == null) {
            ServiceResponse<KieScannerResource> response = createScanner(id, kci);
            if (ResponseType.FAILURE.equals(response.getType())) {
                return response;
            }
        }
        if (KieScannerStatus.STOPPED.equals(mapStatus(kci.getScanner().getStatus())) &&
                resource.getPollInterval() != null) {
            kci.getScanner().start(resource.getPollInterval());
            messages.add(new Message(Severity.INFO, "Kie scanner successfully started with interval " + resource.getPollInterval()));
            return new ServiceResponse<KieScannerResource>(ServiceResponse.ResponseType.SUCCESS,
                    "Kie scanner successfully created.",
                    getScannerResource(kci));
        } else if (!KieScannerStatus.STOPPED.equals(mapStatus(kci.getScanner().getStatus()))) {
            KieScannerStatus kss = mapStatus(kci.getScanner().getStatus());
            messages.add(new Message(Severity.WARN, "Invalid kie scanner status: " + kss));
            return new ServiceResponse<KieScannerResource>(ServiceResponse.ResponseType.FAILURE,
                    "Invalid kie scanner status: " + kss,
                    getScannerResource(kci));
        } else if (resource.getPollInterval() == null) {
            messages.add(new Message(Severity.WARN, "Invalid polling interval: " + resource.getPollInterval()));
            return new ServiceResponse<KieScannerResource>(ServiceResponse.ResponseType.FAILURE,
                    "Invalid polling interval: " + resource.getPollInterval(),
                    getScannerResource(kci));
        }
        messages.add(new Message(Severity.ERROR, "Unknown error starting scanner. Scanner was not started."));
        return new ServiceResponse<KieScannerResource>(ServiceResponse.ResponseType.FAILURE,
                "Unknown error starting scanner. Scanner was not started." + resource,
                getScannerResource(kci));
    }

    private ServiceResponse<KieScannerResource> stopScanner(String id, KieScannerResource resource, KieContainerInstanceImpl kci) {
        List<Message> messages = getMessagesForContainer(id);
        messages.clear();
        if (kci.getScanner() == null) {
            return new ServiceResponse<KieScannerResource>(ServiceResponse.ResponseType.FAILURE,
                    "Invalid call. Scanner is not instantiated. ",
                    getScannerResource(kci));
        }
        if (KieScannerStatus.STARTED.equals(mapStatus(kci.getScanner().getStatus())) ||
                KieScannerStatus.SCANNING.equals(mapStatus(kci.getScanner().getStatus()))) {
            kci.getScanner().stop();
            messages.add(new Message(Severity.INFO, "Kie scanner successfully stopped."));
            return new ServiceResponse<KieScannerResource>(ServiceResponse.ResponseType.SUCCESS,
                    "Kie scanner successfully stopped.",
                    getScannerResource(kci));
        } else {
            KieScannerStatus kss = mapStatus(kci.getScanner().getStatus());

            messages.add(new Message(Severity.WARN, "Invalid kie scanner status: " + kss));
            return new ServiceResponse<KieScannerResource>(ServiceResponse.ResponseType.FAILURE,
                    "Invalid kie scanner status: " + kss,
                    getScannerResource(kci));
        }
    }

    private ServiceResponse<KieScannerResource> scanNow(String id, KieScannerResource resource, KieContainerInstanceImpl kci) {
        List<Message> messages = getMessagesForContainer(id);
        messages.clear();
        if (kci.getScanner() == null) {
            createScanner( id, kci );
        }
        KieScannerStatus kss = mapStatus( kci.getScanner().getStatus() );
        if (KieScannerStatus.STOPPED.equals( kss ) || KieScannerStatus.CREATED.equals( kss ) || KieScannerStatus.STARTED.equals( kss )) {
            kci.getScanner().scanNow();
            messages.add(new Message(Severity.INFO, "Kie scanner successfully invoked."));
            return new ServiceResponse<KieScannerResource>(ServiceResponse.ResponseType.SUCCESS,
                    "Scan successfully executed.",
                    getScannerResource(kci));
        } else {
            messages.add(new Message(Severity.WARN,  "Invalid kie scanner status: " + kss));
            return new ServiceResponse<KieScannerResource>(ServiceResponse.ResponseType.FAILURE,
                    "Invalid kie scanner status: " + kss,
                    getScannerResource(kci));
        }
    }

    private ServiceResponse<KieScannerResource> disposeScanner(String id, KieScannerResource resource, KieContainerInstanceImpl kci) {
        List<Message> messages = getMessagesForContainer(id);
        messages.clear();
        if (kci.getScanner() == null) {
            return new ServiceResponse<KieScannerResource>(ServiceResponse.ResponseType.SUCCESS,
                    "Invalid call. Scanner already disposed. ",
                    getScannerResource(kci));
        }
        if (KieScannerStatus.STARTED.equals(mapStatus(kci.getScanner().getStatus())) ||
                KieScannerStatus.SCANNING.equals(mapStatus(kci.getScanner().getStatus()))) {
            ServiceResponse<KieScannerResource> response = stopScanner(id, resource, kci);
            if (ResponseType.FAILURE.equals(response.getType())) {
                return response;
            }
        }
        kci.getScanner().shutdown();
        kci.setScanner(null);
        messages.add(new Message(Severity.INFO, "Kie scanner successfully created."));
        return new ServiceResponse<KieScannerResource>(ServiceResponse.ResponseType.SUCCESS,
                "Kie scanner successfully shutdown.",
                getScannerResource(kci));
    }

    private ServiceResponse<KieScannerResource> createScanner(String id, KieContainerInstanceImpl kci) {
        if (kci.getScanner() == null) {
            List<Message> messages = getMessagesForContainer(id);
            messages.clear();
            InternalKieScanner scanner = (InternalKieScanner) KieServices.Factory.get().newKieScanner(kci.getKieContainer());
            kci.setScanner(scanner);

            messages.add(new Message(Severity.INFO, "Kie scanner successfully created."));
            return new ServiceResponse<KieScannerResource>(ServiceResponse.ResponseType.SUCCESS,
                    "Kie scanner successfully created.",
                    getScannerResource(kci));
        } else {
            return new ServiceResponse<KieScannerResource>(ServiceResponse.ResponseType.FAILURE,
                    "Error creating the scanner for container " + id + ". Scanner already exists.");

        }
    }

    public ServiceResponse<ReleaseId> getContainerReleaseId(String id) {
        try {
            KieContainerInstanceImpl ci = context.getContainer(id);
            if (ci != null) {
                return new ServiceResponse<ReleaseId>(ServiceResponse.ResponseType.SUCCESS, "ReleaseId for container " + id, ci.getResource().getReleaseId());
            }
            return new ServiceResponse<ReleaseId>(ServiceResponse.ResponseType.FAILURE, "Container " + id + " is not instantiated.");
        } catch (Exception e) {
            logger.error("Error retrieving releaseId for container '" + id + "'", e);
            return new ServiceResponse<ReleaseId>(ServiceResponse.ResponseType.FAILURE, "Error retrieving container releaseId: " +
                    e.getClass().getName() + ": " + e.getMessage());
        }
    }

    public ServiceResponse<ReleaseId> updateContainerReleaseId(String id, ReleaseId releaseId) {
        if( releaseId == null ) {
            logger.error("Error updating releaseId for container '" + id + "'. ReleaseId is null.");
            return new ServiceResponse<ReleaseId>(ServiceResponse.ResponseType.FAILURE, "Error updating releaseId for container " + id + ". ReleaseId is null. ");
        }
        List<Message> messages = getMessagesForContainer(id);
        messages.clear();
        try {
            KieContainerInstanceImpl kci = context.getContainer(id);
            // the following code is subject to a concurrent call to dispose(), but the cost of synchronizing it
            // would likely not be worth it. At this point a decision was made to fail the execution if a concurrent 
            // call do dispose() is executed.
            if (kci != null && kci.getKieContainer() != null) {
                // before upgrade check with all extensions if that is allowed
                KieModuleMetaData metaData = KieModuleMetaData.Factory.newKieModuleMetaData(releaseId, DependencyFilter.COMPILE_FILTER);
                Map<String, Object> parameters = new HashMap<String, Object>();
                parameters.put(KieServerConstants.KIE_SERVER_PARAM_MODULE_METADATA, metaData);
                // process server extensions
                List<KieServerExtension> extensions = context.getServerExtensions();
                for (KieServerExtension extension : extensions) {
                    boolean allowed = extension.isUpdateContainerAllowed(id, kci, parameters);
                    if (!allowed) {
                        String message = (String)parameters.get(KieServerConstants.FAILURE_REASON_PROP);
                        logger.warn("Container {} (for release id {}) on {} cannot be updated due to {}", id, releaseId, extension, message);
                        if (messages != null) {
                            messages.add(new Message(Severity.WARN, message));
                        }
                        return new ServiceResponse<ReleaseId>(ServiceResponse.ResponseType.FAILURE, message);
                    }
                    logger.debug("Container {} (for release id {}) on {} ready to be updated", id, releaseId, extension);
                }
                kci.clearJaxbClasses();
                kci.disposeMarshallers();
                Results results = kci.getKieContainer().updateToVersion(releaseId);
                if (results.hasMessages(Level.ERROR)) {

                    Message error = new Message(Severity.WARN, "Error updating releaseId for container " + id + " to version " + releaseId);
                    for (org.kie.api.builder.Message builderMsg : results.getMessages()) {
                        error.addMessage(builderMsg.getText());
                    }
                    messages.add(error);
                    logger.error("Error updating releaseId for container " + id + " to version " + releaseId + "\nMessages: " + results.getMessages());
                    return new ServiceResponse<ReleaseId>(ServiceResponse.ResponseType.FAILURE, "Error updating release id on container " + id + " to " + releaseId, kci.getResource().getReleaseId());
                } else {
                    // once the upgrade was successful, notify all extensions so they can be upgraded (if needed)
                    for (KieServerExtension extension : extensions) {
                        extension.updateContainer(id, kci, parameters);
                        logger.debug("Container {} (for release id {}) on {} updated successfully", id, releaseId, extension);
                    }
                    // store the current state of the server
                    KieServerState currentState = repository.load(KieServerEnvironment.getServerId());

                    List<KieContainerResource> containers = new ArrayList<KieContainerResource>();
                    for (KieContainerResource containerResource : currentState.getContainers()) {
                        if ( id.equals(containerResource.getContainerId()) ) {
                            containerResource.setReleaseId(releaseId);
                            containerResource.setResolvedReleaseId(new ReleaseId(kci.getKieContainer().getContainerReleaseId()));
                        }
                        containers.add(containerResource);
                    }

                    currentState.setContainers(new HashSet<KieContainerResource>(containers));
                    repository.store(KieServerEnvironment.getServerId(), currentState);

                    logger.info("Container {} successfully updated to release id {}", id, releaseId);

                    messages.add(new Message(Severity.INFO, "Release id successfully updated for container " + id));
                    return new ServiceResponse<ReleaseId>(ServiceResponse.ResponseType.SUCCESS, "Release id successfully updated.", kci.getResource().getReleaseId());
                }
            } else {
                // no container yet, attempt to create it
                KieContainerResource containerResource = new KieContainerResource(id, releaseId, KieContainerStatus.STARTED);

                ServiceResponse<KieContainerResource> response = createContainer(id, containerResource);
                if (response.getType().equals(ResponseType.SUCCESS)) {
                    kci = context.getContainer(id);
                    return new ServiceResponse<ReleaseId>(ServiceResponse.ResponseType.SUCCESS, "Release id successfully updated.", kci.getResource().getReleaseId());
                } else {
                    return new ServiceResponse<ReleaseId>(ServiceResponse.ResponseType.FAILURE, "Container " + id + " is not instantiated.");
                }
            }
        } catch (Exception e) {
            if (messages != null) {
                messages.add(new Message(Severity.WARN, "Error updating releaseId for container '" + id + "' due to "+ e.getMessage()));
            }
            logger.error("Error updating releaseId for container '" + id + "'", e);
            return new ServiceResponse<ReleaseId>(ServiceResponse.ResponseType.FAILURE, "Error updating releaseId for container " + id + ": " +
                    e.getClass().getName() + ": " + e.getMessage());
        }
    }

    public ServiceResponse<KieServerStateInfo> getServerState() {
        try {
            KieServerState currentState = repository.load(KieServerEnvironment.getServerId());
            KieServerStateInfo state = new KieServerStateInfo(currentState.getControllers(), currentState.getConfiguration(), currentState.getContainers());
            return new ServiceResponse<KieServerStateInfo>(ServiceResponse.ResponseType.SUCCESS,
                    "Successfully loaded server state for server id " + KieServerEnvironment.getServerId(), state);
        } catch (Exception e) {
            logger.error("Error when loading server state due to {}", e.getMessage(), e);
            return new ServiceResponse<KieServerStateInfo>(ResponseType.FAILURE, "Error when loading server state due to " + e.getMessage());
        }
    }

    private KieScannerStatus mapStatus(InternalKieScanner.Status status) {
        switch (status) {
            case STARTING:
                return KieScannerStatus.CREATED;
            case RUNNING:
                return KieScannerStatus.STARTED;
            case SCANNING:
            case UPDATING:
                return KieScannerStatus.SCANNING;
            case STOPPED:
                return KieScannerStatus.STOPPED;
            case SHUTDOWN:
                return KieScannerStatus.DISPOSED;
            default:
                return KieScannerStatus.UNKNOWN;
        }
    }

    protected KieServerController getController() {
        KieServerController controller = new DefaultRestControllerImpl(context);
        Iterator<KieServerController> it = kieControllers.iterator();
        if (it != null && it.hasNext()) {
            controller = it.next();
        }

        return controller;
    }

    protected ContainerManager getContainerManager() {
        try {
            return InitialContext.doLookup("java:module/ContainerManagerEJB");
        } catch (Exception e) {
            logger.debug("Unable to find JEE version of ContainerManager suing default one");
            return new ContainerManager();
        }
    }

    protected List<KieServerExtension> sortKnownExtensions() {
        List<KieServerExtension> extensions = new ArrayList<KieServerExtension>();

        for (KieServerExtension extension : serverExtensions) {
            extensions.add(extension);
        }

        Collections.sort(extensions, new Comparator<KieServerExtension>() {
            @Override
            public int compare(KieServerExtension e1, KieServerExtension e2) {
                return e1.getStartOrder().compareTo(e2.getStartOrder());
            }
        });

        return extensions;
    }

    public void addServerMessage(Message message) {
        this.serverMessages.add(message);
    }

    public void addServerStatusMessage(KieServerInfo kieServerInfo) {
        StringBuilder serverInfoMsg = new StringBuilder();
        serverInfoMsg
                .append("Server ")
                .append(kieServerInfo)
                .append("started successfully at ")
                .append(new Date());

        serverMessages.add(new Message(Severity.INFO, serverInfoMsg.toString()));
    }

    protected List<Message> getMessagesForContainer(String containerId) {
        List<Message> messages = containerMessages.get(containerId);

        if (messages == null) {
            messages = new CopyOnWriteArrayList<Message>();
            containerMessages.put(containerId, messages);
        }

        return messages;
    }

    @Override
    public String toString() {
        return "KieServer{" +
                "id='" + KieServerEnvironment.getServerId() + '\'' +
                "name='" + KieServerEnvironment.getServerName() + '\'' +
                "version='" + KieServerEnvironment.getVersion() + '\'' +
                "location='" + kieServerLocation + '\'' +
                '}';
    }
}
