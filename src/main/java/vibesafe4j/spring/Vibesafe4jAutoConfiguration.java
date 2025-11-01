package vibesafe4j.spring;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.aot.hint.TypeReference;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;
import vibesafe4j.Func;
import vibesafe4j.Vibesafe4j;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * scans all components for the tell-tale {@link vibesafe4j.Func} annotation and
 * automatically registers beans
 */
@Configuration
@AutoConfigureAfter(OpenAiChatAutoConfiguration.class)
class Vibesafe4jAutoConfiguration {

	@Bean
	static FuncBeanRegistrar funcBeanRegistrar() {
		return new FuncBeanRegistrar();
	}

	@Bean
	@ConditionalOnClass(ChatClient.class)
	@ConditionalOnBean(ChatClient.class)
	@ConditionalOnMissingBean(AiClient.class)
	AiClient defaultSpringAiAiClient(ChatClient.Builder builder) {
		var ai = builder.build();
		return prompt -> ai.prompt(prompt).call().content();
	}

}

abstract class FuncBeanUtils {

	static String generatedBeanName(Class<?> clzz) {
		return "generated" + clzz.getSimpleName();
	}

}

class FuncBeanRegistrar implements BeanDefinitionRegistryPostProcessor {

	// todo what's this look like in the wacky world of graalvm ?

	// todo a BeanFactoryInitializationAotProcessor that writes out the source code and
	// then adds the generated class name
	// to programmatically register a new instance using Javapoet. Make sure to create a
	// new class! do <em>not</em> overload this class!

	// todo should we give the model information about the parameters?

	@Override
	public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
		if (registry instanceof ConfigurableListableBeanFactory beanFactory) {
			var aiClientBeanDefinitionName = this.findBeanDefinition(beanFactory);
			Assert.hasText(aiClientBeanDefinitionName,
					"there should be at least one bean of type " + AiClient.class.getName());
			var lookup = (Function<String, String>) (String prompt) -> {
				var aiClientBean = beanFactory.getBean(aiClientBeanDefinitionName, AiClient.class);
				return aiClientBean.call(prompt);
			};
			var packages = AutoConfigurationPackages.get(beanFactory);
			for (var pkg : packages) {
				var types = FuncDiscoveryUtils.findAnnotatedInterfacesInPackage(pkg);
				for (var tr : types) {
					try {
						var clzz = Class.forName(tr.getName());
						var supplier = (Supplier<?>) () -> {
							try {
								return Vibesafe4j.build(lookup, clzz);
							}
							catch (Exception e) {
								throw new RuntimeException(e);
							}
						};
						var beanName = FuncBeanUtils.generatedBeanName(clzz);
						if (!beanFactory.containsBean(beanName)) {
							var rbd = new RootBeanDefinition();
							rbd.setBeanClass(clzz);
							rbd.setInstanceSupplier(supplier);
							registry.registerBeanDefinition(beanName, rbd);
						}
					} //
					catch (Exception e) {
						throw new RuntimeException(e);
					}
				}
			}
		}
	}

	private String findBeanDefinition(ConfigurableListableBeanFactory factory) {
		var beanNames = factory.getBeanDefinitionNames();
		for (var beanName : beanNames) {
			var type = factory.getType(beanName);
			if (AiClient.class.isAssignableFrom(type)) {
				return beanName;
			}
		}
		return null;
	}

}

abstract class FuncDiscoveryUtils {

	private static final TypeFilter METHOD_TYPE_FILTER = (metadataReader, _) -> {
		try {
			var clazz = Class.forName(metadataReader.getClassMetadata().getClassName());
			return isValidInterface(clazz);
		} //
		catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		}
	};

	static Set<TypeReference> findAnnotatedInterfacesInPackage(String packageName) {
		return findClassesInPackage(packageName, METHOD_TYPE_FILTER);
	}

	private static boolean isValidInterface(Class<?> clazz) {
		var matches = new AtomicBoolean(false);
		ReflectionUtils.doWithMethods(clazz, method -> {
			if (method.getAnnotationsByType(Func.class).length > 0) {
				matches.set(true);
			}
		});
		return Modifier.isInterface(clazz.getModifiers()) && matches.get();
	}

	private static Set<TypeReference> findClassesInPackage(String packageName, TypeFilter typeFilter) {
		var scanner = new ClassPathScanningCandidateComponentProvider(false) {

			@Override
			protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
				return true;
			}

			@Override
			protected boolean isCandidateComponent(MetadataReader metadataReader) throws IOException {
				return typeFilter.match(metadataReader, this.getMetadataReaderFactory());
			}
		};
		scanner.addIncludeFilter(typeFilter);
		var candidateComponents = scanner.findCandidateComponents(packageName);
		return candidateComponents //
			.stream() //
			.map((bd) -> TypeReference.of(Objects.requireNonNull(bd.getBeanClassName())))//
			.collect(Collectors.toUnmodifiableSet());
	}

}