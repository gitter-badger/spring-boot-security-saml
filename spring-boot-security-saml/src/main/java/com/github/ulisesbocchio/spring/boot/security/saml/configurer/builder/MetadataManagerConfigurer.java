package com.github.ulisesbocchio.spring.boot.security.saml.configurer.builder;

import com.github.ulisesbocchio.spring.boot.security.saml.configurer.ServiceProviderSecurityBuilder;
import com.github.ulisesbocchio.spring.boot.security.saml.configurer.ServiceProviderSecurityConfigurer;
import com.github.ulisesbocchio.spring.boot.security.saml.properties.SAMLSSOProperties;
import com.github.ulisesbocchio.spring.boot.security.saml.properties.SAMLSSOProperties.ExtendedMetadataDelegateConfiguration;
import com.github.ulisesbocchio.spring.boot.security.saml.properties.SAMLSSOProperties.MetadataManagerConfiguration;
import com.github.ulisesbocchio.spring.boot.security.saml.resource.SpringResourceWrapperOpenSAMLResource;
import lombok.SneakyThrows;
import org.assertj.core.util.VisibleForTesting;
import org.opensaml.saml2.metadata.provider.*;
import org.opensaml.util.resource.ResourceException;
import org.opensaml.xml.parse.ParserPool;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.config.annotation.SecurityConfigurerAdapter;
import org.springframework.security.saml.metadata.CachingMetadataManager;
import org.springframework.security.saml.metadata.ExtendedMetadata;
import org.springframework.security.saml.metadata.ExtendedMetadataDelegate;
import org.springframework.security.saml.metadata.MetadataManager;

import java.util.*;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toSet;

/**
 * Builder configurer that takes care of configuring/customizing the {@link MetadataManager} bean.
 * <p>
 * Common strategy across most internal configurers is to first give priority to a Spring Bean if present in the
 * Context. So if not {@link MetadataManager} bean is defined, priority goes to a custom MetadataManager provided
 * explicitly to this configurer through the constructor. And if not provided through the constructor, a default
 * implementation is instantiated that is configurable through the DSL methods.
 * </p>
 * <p>
 * This configurer also reads the values from {@link SAMLSSOProperties#getMetadataManager()} and
 * {@link SAMLSSOProperties#getExtendedDelegate()} if no custom MetadataManager
 * is provided, for some DSL methods if they are not used. In other words, the user is able to configure the
 * MetadataManager through the
 * following properties:
 * <pre>
 *     saml.sso.metadataManager.defaultIDP
 *     saml.sso.metadataManager.hostedSPName
 *     saml.sso.metadataManager.refreshCheckInterval
 *     saml.sso.extendedDelegate.metadataTrustedKeys
 *     saml.sso.extendedDelegate.metadataTrustCheck
 *     saml.sso.extendedDelegate.forceMetadataRevocationCheck
 *     saml.sso.extendedDelegate.metadataRequireSignature
 *     saml.sso.extendedDelegate.requireValidMetadata
 *     saml.sso.idp.metadataLocation
 * </pre>
 * </p>
 *
 * @author Ulises Bocchio
 */
public class MetadataManagerConfigurer extends SecurityConfigurerAdapter<ServiceProviderSecurityConfigurer, ServiceProviderSecurityBuilder> {

    List<MetadataProvider> metadataProviders = new ArrayList<>();
    private MetadataFilter metadataFilter = null;
    private ExtendedMetadata extendedMetadata = null;
    private Boolean forceMetadataRevocationCheck = null;
    private Boolean metadataRequireSignature = null;
    private Boolean metadataTrustCheck = null;
    private Set<String> metadataTrustedKeys = null;
    private Boolean requireValidMetadata = null;
    private String defaultIDP;
    private String hostedSPName;
    private Long refreshCheckInterval;
    private List<String> metadataProviderLocations = new ArrayList<>();
    private MetadataManager metadataManager;
    private MetadataManager metadataManagerBean;
    private ResourceLoader resourceLoader;
    private ExtendedMetadataDelegateConfiguration extendedDelegateConfig;
    private MetadataManagerConfiguration managerConfig;
    private SAMLSSOProperties.IdentityProvidersConfiguration idpConfig;

    public MetadataManagerConfigurer(MetadataManager metadataManager) {
        this.metadataManager = metadataManager;
    }

    public MetadataManagerConfigurer() {
    }

    @Override
    public void init(ServiceProviderSecurityBuilder builder) throws Exception {
        resourceLoader = builder.getSharedObject(ResourceLoader.class);
        metadataManagerBean = builder.getSharedObject(MetadataManager.class);
        extendedDelegateConfig = builder.getSharedObject(SAMLSSOProperties.class).getExtendedDelegate();
        managerConfig = builder.getSharedObject(SAMLSSOProperties.class).getMetadataManager();
        idpConfig = builder.getSharedObject(SAMLSSOProperties.class).getIdps();
    }

    @Override
    public void configure(ServiceProviderSecurityBuilder builder) throws Exception {
        extendedMetadata = builder.getSharedObject(ExtendedMetadata.class);

        if (metadataManagerBean == null) {
            if (metadataManager == null) {
                metadataManager = createDefaultMetadataManager();
                metadataManager.setDefaultIDP(Optional.ofNullable(defaultIDP).orElseGet(managerConfig::getDefaultIDP));
                metadataManager.setHostedSPName(Optional.ofNullable(hostedSPName).orElseGet(managerConfig::getHostedSPName));
                metadataManager.setRefreshCheckInterval(Optional.ofNullable(refreshCheckInterval).orElseGet(managerConfig::getRefreshCheckInterval));
            }

            if(metadataManager.getProviders() == null || metadataManager.getProviders().size() == 0) {
                if (metadataProviders.size() == 0 && metadataProviderLocations.size() > 0) {
                    for (String metadataLocation : metadataProviderLocations) {
                        MetadataProvider providerFromLocation =createDefaultMetadataProvider(metadataLocation);
                        metadataProviders.add(postProcess(providerFromLocation));
                    }
                }

                if (metadataProviders.size() == 0) {
                    String metadataLocation = idpConfig.getMetadataLocation();
                    if(metadataLocation != null && !metadataLocation.trim().equals("")) {
                        for (String location : metadataLocation.split(",")) {
                            MetadataProvider providerFromProperties = createDefaultMetadataProvider(location);
                            metadataProviders.add(postProcess(providerFromProperties));
                        }
                    }
                }
            }

            List<MetadataProvider> extendedMetadataDelegates = metadataProviders.stream()
                    .map(this::setParserPool)
                    .map(this::getExtendedProvider)
                    .collect(Collectors.toList());


            metadataManager.setProviders(extendedMetadataDelegates);
            builder.setSharedObject(MetadataManager.class, metadataManager);
        }

    }

    @VisibleForTesting
    protected MetadataProvider createDefaultMetadataProvider(String location) throws ResourceException, MetadataProviderException {
        return new ResourceBackedMetadataProvider(new Timer(),
                new SpringResourceWrapperOpenSAMLResource(resourceLoader.getResource(location.trim())));
    }

    @VisibleForTesting
    protected CachingMetadataManager createDefaultMetadataManager() throws MetadataProviderException {
        return new CachingMetadataManager(null);
    }

    @VisibleForTesting
    protected ExtendedMetadataDelegate createDefaultExtendedMetadataDelegate(MetadataProvider provider) {
        return new ExtendedMetadataDelegate(provider, extendedMetadata);
    }

    private MetadataProvider setParserPool(MetadataProvider provider) {
        if (provider instanceof AbstractMetadataProvider) {
            ((AbstractMetadataProvider) provider).setParserPool(getBuilder().getSharedObject(ParserPool.class));
        }
        return provider;
    }

    @SneakyThrows
    private ExtendedMetadataDelegate getExtendedProvider(MetadataProvider provider) {
        if (provider instanceof ExtendedMetadataDelegate) {
            return (ExtendedMetadataDelegate) provider;
        }
        ExtendedMetadataDelegate delegate = createDefaultExtendedMetadataDelegate(provider);

        delegate.setForceMetadataRevocationCheck(Optional.ofNullable(forceMetadataRevocationCheck)
                .orElseGet(extendedDelegateConfig::isForceMetadataRevocationCheck));

        delegate.setMetadataRequireSignature(Optional.ofNullable(metadataRequireSignature)
                .orElseGet(extendedDelegateConfig::isMetadataRequireSignature));

        delegate.setMetadataTrustCheck(Optional.ofNullable(metadataTrustCheck)
                .orElseGet(extendedDelegateConfig::isMetadataTrustCheck));

        delegate.setMetadataTrustedKeys(Optional.ofNullable(metadataTrustedKeys)
                .orElseGet(extendedDelegateConfig::getMetadataTrustedKeys));

        delegate.setRequireValidMetadata(Optional.ofNullable(requireValidMetadata)
                .orElseGet(extendedDelegateConfig::isRequireValidMetadata));

        delegate.setMetadataFilter(Optional.ofNullable(metadataFilter)
                .map(this::postProcess)
                .orElse(null));

        return postProcess(delegate);
    }

    /**
     * Sets name of IDP to be used as default.
     * <p>
     * Alternatively use property:
     * <pre>
     *      saml.sso.metadataManager.defaultIDP
     * </pre>
     * </p>
     *
     * @param defaultIDP name of IDP to be used as default.
     * @return this configurer for further customization
     */
    public MetadataManagerConfigurer defaultIDP(String defaultIDP) {
        this.defaultIDP = defaultIDP;
        return this;
    }

    /**
     * Sets nameID of SP hosted on this machine. This can either be called from springContext or automatically during
     * invocation of metadata generation filter.
     * <p>
     * Alternatively use property:
     * <pre>
     *      saml.sso.metadataManager.hostedSPName
     * </pre>
     * </p>
     *
     * @param hostedSPName name of metadata describing SP hosted on this machine
     * @return this configurer for further customization
     */
    public MetadataManagerConfigurer hostedSPName(String hostedSPName) {
        this.hostedSPName = hostedSPName;
        return this;
    }

    /**
     * Interval in milliseconds used for re-verification of metadata and their reload. Upon trigger each provider
     * is asked to return it's metadata, which might trigger their reloading. In case metadata is reloaded the manager
     * is notified and automatically refreshes all internal data by calling refreshMetadata.
     * <p>
     * In case the value is smaller than zero the timer is not created.
     * </p>
     * Default is {@code -1}.
     * <p>
     * Alternatively use property:
     * <pre>
     *      saml.sso.metadataManager.refreshCheckInterval
     * </pre>
     * </p>
     *
     * @param refreshCheckInterval the refresh interval in milliseconds.
     * @return this configurer for further customization
     */
    public MetadataManagerConfigurer refreshCheckInterval(long refreshCheckInterval) {
        this.refreshCheckInterval = refreshCheckInterval;
        return this;
    }

    /**
     * Adds a new {@link MetadataProvider} to the {@link MetadataManager}. Can be invoked multiple times.
     * Takes precedence over {@link #metadataLocations(String...)}.
     *
     * @param provider the provider to add to the {@link MetadataManager}.
     * @return this configurer for further customization
     */
    public MetadataManagerConfigurer metadataProvider(MetadataProvider provider) {
        metadataProviders.add(provider);
        return this;
    }

    /**
     * Sets the provided {@link MetadataProvider}s in the {@link MetadataManager}. Invocation if this method overrides
     * any existing {@link MetadataProvider} previously set with {@link #metadataProvider(MetadataProvider)}.
     * Takes precedence over {@link #metadataLocations(String...)}.
     *
     * @param providers the metadata providers to use.
     * @return this configurer for further customization
     */
    public MetadataManagerConfigurer metadataProviders(MetadataProvider... providers) {
        metadataProviders = Arrays.asList(providers);
        return this;
    }

    /**
     * Specify the location(s) of the metadata files to be loaded as {@link ResourceBackedMetadataProvider}. Not
     * relevant is using {@link #metadataProvider(MetadataProvider)}, {@link #metadataProviders(List)}, or
     * {@link #metadataProviders(MetadataProvider...)}
     * <p>
     * Alternatively use property:
     * <pre>
     *      saml.sso.idp.metadataLocation
     * </pre>
     * </p>
     *
     * @param providerLocation the metadata files to load.
     * @return this configurer for further customization
     */
    public MetadataManagerConfigurer metadataLocations(String... providerLocation) {
        metadataProviderLocations.addAll(Arrays.asList(providerLocation));
        return this;
    }

    /**
     * Sets the provided {@link MetadataProvider}s in the {@link MetadataManager}. Invocation if this method overrides
     * any existing {@link MetadataProvider} previously set with {@link #metadataProvider(MetadataProvider)}.
     * Takes precedence over {@link #metadataLocations(String...)}.
     *
     * @param providers the metadata providers to use.
     * @return this configurer for further customization
     */
    public MetadataManagerConfigurer metadataProviders(List<MetadataProvider> providers) {
        metadataProviders = new ArrayList<>(providers);
        return this;
    }

    /**
     * Sets the metadata filter applied to the metadata.
     *
     * @param filter the metadata filter applied to the metadata
     * @return this configurer for further customization
     */
    public MetadataManagerConfigurer metadataFilter(MetadataFilter filter) {
        metadataFilter = filter;
        return this;
    }

    /**
     * Determines whether check for certificate revocation should always be done as part of the PKIX validation.
     * Revocation is evaluated by the underlaying JCE implementation and depending on configuration may include CRL and
     * OCSP verification of the certificate in question. When set to false revocation is only performed when
     * MetadataManager includes CRLs.
     * Default is {@code false}.
     * <p>
     * Alternatively use property:
     * <pre>
     *      saml.sso.extendedDelegate.forceMetadataRevocationCheck
     * </pre>
     * </p>
     *
     * @param forceMetadataRevocationCheck revocation flag.
     * @return this configurer for further customization
     */
    public MetadataManagerConfigurer forceMetadataRevocationCheck(boolean forceMetadataRevocationCheck) {
        this.forceMetadataRevocationCheck = forceMetadataRevocationCheck;
        return this;
    }

    /**
     * When set to true metadata from this provider should only be accepted when correctly signed and verified.
     * Metadata
     * with an invalid signature or signed by a not-trusted credential will be ignored.
     * Default is {@code false}.
     * <p>
     * Alternatively use property:
     * <pre>
     *      saml.sso.extendedDelegate.metadataRequireSignature
     * </pre>
     * </p>
     *
     * @param metadataRequireSignature flag to set.
     * @return this configurer for further customization
     */
    public MetadataManagerConfigurer metadataRequireSignature(boolean metadataRequireSignature) {
        this.metadataRequireSignature = metadataRequireSignature;
        return this;
    }

    /**
     * When true metadata signature will be verified for trust using PKIX with metadataTrustedKeys
     * as anchors.
     * Default is {@code false}.
     * <p>
     * Alternatively use property:
     * <pre>
     *      saml.sso.extendedDelegate.metadataTrustCheck
     * </pre>
     * </p>
     *
     * @param metadataTrustCheck flag to set.
     * @return this configurer for further customization
     */
    public MetadataManagerConfigurer metadataTrustCheck(boolean metadataTrustCheck) {
        this.metadataTrustCheck = metadataTrustCheck;
        return this;
    }

    /**
     * Keys stored in the KeyManager which can be used to verify whether signature of the metadata is trusted.
     * If not set any key stored in the keyManager is considered as trusted.
     * <p>
     * Alternatively use property:
     * <pre>
     *      saml.sso.extendedDelegate.metadataTrustedKeys
     * </pre>
     * </p>
     *
     * @param metadataTrustedKeys the names of the trusted keys.
     * @return this configurer for further customization
     */
    public MetadataManagerConfigurer metadataTrustedKeys(String... metadataTrustedKeys) {
        this.metadataTrustedKeys = Arrays.stream(metadataTrustedKeys).collect(toSet());
        return this;
    }

    /**
     * Sets whether the metadata returned by queries must be valid.
     * Default is {@code false}.
     * <p>
     * Alternatively use property:
     * <pre>
     *      saml.sso.extendedDelegate.requireValidMetadata
     * </pre>
     * </p>
     *
     * @param requireValidMetadata whether the metadata returned by queries must be valid.
     * @return this configurer for further customization
     */
    public MetadataManagerConfigurer requireValidMetadata(boolean requireValidMetadata) {
        this.requireValidMetadata = requireValidMetadata;
        return this;
    }
}
