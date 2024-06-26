/*
 * Copyright 2002-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.test.context.bean.override;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.ResolvableType;
import org.springframework.util.StringUtils;

/**
 * A {@link BeanFactoryPostProcessor} implementation that processes test classes
 * and adapt the {@link BeanFactory} for any {@link BeanOverride} it may define.
 *
 * <p>A set of classes from which to parse {@link OverrideMetadata} must be
 * provided to this processor. Each test class is expected to use any
 * annotation meta-annotated with {@link BeanOverride @BeanOverride} to mark
 * beans to override. The {@link BeanOverrideParsingUtils#hasBeanOverride(Class)}
 * method can be used to check if a class matches the above criteria.
 *
 * <p>The provided classes are fully parsed at creation to build a metadata set.
 * This processor implements several {@link BeanOverrideStrategy overriding
 * strategy} and chooses the correct one according to each override metadata
 * {@link OverrideMetadata#getStrategy()} method. Additionally, it provides
 * support for injecting the overridden bean instances into their corresponding
 * annotated {@link Field fields}.
 *
 * @author Simon Baslé
 * @since 6.2
 */
class BeanOverrideBeanFactoryPostProcessor implements BeanFactoryPostProcessor, Ordered {


	private final BeanOverrideRegistrar overrideRegistrar;


	/**
	 * Create a new {@code BeanOverrideBeanFactoryPostProcessor} instance with
	 * the given {@link BeanOverrideRegistrar}, which contains a set of parsed
	 * {@link OverrideMetadata}.
	 * @param overrideRegistrar the {@link BeanOverrideRegistrar} used to track
	 * metadata
	 */
	public BeanOverrideBeanFactoryPostProcessor(BeanOverrideRegistrar overrideRegistrar) {
		this.overrideRegistrar = overrideRegistrar;
	}


	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE - 10;
	}


	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		if (!(beanFactory instanceof BeanDefinitionRegistry registry)) {
			throw new IllegalStateException("Cannot process bean override with a BeanFactory " +
					"that doesn't implement BeanDefinitionRegistry: " + beanFactory.getClass());
		}
		postProcessWithRegistry(beanFactory, registry);
	}

	private void postProcessWithRegistry(ConfigurableListableBeanFactory beanFactory, BeanDefinitionRegistry registry) {
		for (OverrideMetadata metadata : this.overrideRegistrar.getOverrideMetadata()) {
			registerBeanOverride(beanFactory, registry, metadata);
		}
	}

	/**
	 * Copy certain details of a {@link BeanDefinition} to the definition created by
	 * this processor for a given {@link OverrideMetadata}.
	 * <p>The default implementation copies the {@linkplain BeanDefinition#isPrimary()
	 * primary flag} and the {@linkplain BeanDefinition#getScope() scope}.
	 */
	protected void copyBeanDefinitionDetails(BeanDefinition from, RootBeanDefinition to) {
		to.setPrimary(from.isPrimary());
		to.setScope(from.getScope());
	}

	private void registerBeanOverride(ConfigurableListableBeanFactory beanFactory, BeanDefinitionRegistry registry,
			OverrideMetadata overrideMetadata) {

		switch (overrideMetadata.getStrategy()) {
			case REPLACE_DEFINITION ->
					registerReplaceDefinition(beanFactory, registry, overrideMetadata, true);
			case REPLACE_OR_CREATE_DEFINITION ->
					registerReplaceDefinition(beanFactory, registry, overrideMetadata, false);
			case WRAP_BEAN -> registerWrapBean(beanFactory, overrideMetadata);
		}
	}

	private void registerReplaceDefinition(ConfigurableListableBeanFactory beanFactory, BeanDefinitionRegistry registry,
			OverrideMetadata overrideMetadata, boolean enforceExistingDefinition) {

		RootBeanDefinition beanDefinition = createBeanDefinition(overrideMetadata);
		String beanName = overrideMetadata.getBeanName();

		BeanDefinition existingBeanDefinition = null;
		if (beanFactory.containsBeanDefinition(beanName)) {
			existingBeanDefinition = beanFactory.getBeanDefinition(beanName);
			copyBeanDefinitionDetails(existingBeanDefinition, beanDefinition);
			registry.removeBeanDefinition(beanName);
		}
		else if (enforceExistingDefinition) {
			throw new IllegalStateException("Unable to override bean '" + beanName + "'; there is no" +
					" bean definition to replace with that name");
		}
		registry.registerBeanDefinition(beanName, beanDefinition);

		Object override = overrideMetadata.createOverride(beanName, existingBeanDefinition, null);
		if (beanFactory.isSingleton(beanName)) {
			// Now we have an instance (the override) that we can register.
			// At this stage we don't expect a singleton instance to be present,
			// and this call will throw if there is such an instance already.
			beanFactory.registerSingleton(beanName, override);
		}

		overrideMetadata.track(override, beanFactory);
		this.overrideRegistrar.registerNameForMetadata(overrideMetadata, beanName);
	}

	/**
	 * Check that the expected bean name is registered and matches the type to override.
	 * <p>If so, put the override metadata in the early tracking map.
	 * <p>The map will later be checked to see if a given bean should be wrapped
	 * upon creation, during the {@link WrapEarlyBeanPostProcessor#getEarlyBeanReference(Object, String)}
	 * phase.
	 */
	private void registerWrapBean(ConfigurableListableBeanFactory beanFactory, OverrideMetadata metadata) {
		Set<String> existingBeanNames = getExistingBeanNames(beanFactory, metadata.getBeanType());
		String beanName = metadata.getBeanName();
		if (!existingBeanNames.contains(beanName)) {
			throw new IllegalStateException("Unable to override bean '" + beanName + "' by wrapping," +
					" no existing bean instance by this name of type " + metadata.getBeanType());
		}
		this.overrideRegistrar.markWrapEarly(metadata, beanName);
		this.overrideRegistrar.registerNameForMetadata(metadata, beanName);
	}

	private RootBeanDefinition createBeanDefinition(OverrideMetadata metadata) {
		RootBeanDefinition definition = new RootBeanDefinition();
		definition.setTargetType(metadata.getBeanType());
		return definition;
	}

	private Set<String> getExistingBeanNames(ConfigurableListableBeanFactory beanFactory, ResolvableType resolvableType) {
		Set<String> beans = new LinkedHashSet<>(
				Arrays.asList(beanFactory.getBeanNamesForType(resolvableType, true, false)));
		Class<?> type = resolvableType.resolve(Object.class);
		for (String beanName : beanFactory.getBeanNamesForType(FactoryBean.class, true, false)) {
			beanName = BeanFactoryUtils.transformedBeanName(beanName);
			BeanDefinition beanDefinition = beanFactory.getBeanDefinition(beanName);
			Object attribute = beanDefinition.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE);
			if (resolvableType.equals(attribute) || type.equals(attribute)) {
				beans.add(beanName);
			}
		}
		beans.removeIf(ScopedProxyUtils::isScopedTarget);
		return beans;
	}


	static final class WrapEarlyBeanPostProcessor implements SmartInstantiationAwareBeanPostProcessor,
			PriorityOrdered {

		private final BeanOverrideRegistrar overrideRegistrar;

		private final Map<String, Object> earlyReferences;


		private WrapEarlyBeanPostProcessor(BeanOverrideRegistrar registrar) {
			this.overrideRegistrar = registrar;
			this.earlyReferences = new ConcurrentHashMap<>(16);
		}


		@Override
		public int getOrder() {
			return Ordered.HIGHEST_PRECEDENCE;
		}

		@Override
		public Object getEarlyBeanReference(Object bean, String beanName) throws BeansException {
			if (bean instanceof FactoryBean) {
				return bean;
			}
			this.earlyReferences.put(getCacheKey(bean, beanName), bean);
			return this.overrideRegistrar.wrapIfNecessary(bean, beanName);
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
			if (bean instanceof FactoryBean) {
				return bean;
			}
			if (this.earlyReferences.remove(getCacheKey(bean, beanName)) != bean) {
				return this.overrideRegistrar.wrapIfNecessary(bean, beanName);
			}
			return bean;
		}

		private String getCacheKey(Object bean, String beanName) {
			return (StringUtils.hasLength(beanName) ? beanName : bean.getClass().getName());
		}

	}

}
