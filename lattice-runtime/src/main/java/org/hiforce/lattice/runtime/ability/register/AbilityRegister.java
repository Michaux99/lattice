package org.hiforce.lattice.runtime.ability.register;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.hiforce.lattice.annotation.model.*;
import org.hiforce.lattice.exception.LatticeRuntimeException;
import org.hiforce.lattice.message.Message;
import org.hiforce.lattice.model.ability.IAbility;
import org.hiforce.lattice.model.register.AbilityInstSpec;
import org.hiforce.lattice.model.register.AbilitySpec;
import org.hiforce.lattice.model.register.ExtensionSpec;
import org.hiforce.lattice.runtime.Lattice;
import org.hiforce.lattice.runtime.cache.ability.AbilityCache;
import org.hiforce.lattice.spi.LatticeAnnotationSpiFactory;
import org.hiforce.lattice.spi.annotation.AbilityAnnotationParser;
import org.hiforce.lattice.utils.LatticeClassUtils;
import org.springframework.aop.support.AopUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

import static org.hiforce.lattice.runtime.utils.LatticeBeanUtils.getAndCreateSpringBeanViaClass;
import static org.hiforce.lattice.spi.annotation.PriorityAnnotationParser.getPriorityAnnotationInfo;
import static org.hiforce.lattice.utils.LatticeAnnotationUtils.getExtensionAnnotation;

/**
 * @author Rocky Yu
 * @since 2022/9/15
 */
@Slf4j
public class AbilityRegister {

    private static final ThreadLocal<ClassLoader> CLASS_LOADER_THREAD_LOCAL = new ThreadLocal<>();

    private static AbilityRegister instance;

    private AbilityRegister() {

    }

    public static AbilityRegister getInstance() {
        if (null == instance) {
            instance = new AbilityRegister();
        }
        return instance;
    }

    public List<AbilitySpec> register(AbilityBuildRequest regDTO) {

        List<AbilitySpec> abilitySpecList = new ArrayList<>();
        // 遍历所有Ability实现类
        for (Class<?> currentClass : regDTO.getClassSet()) {
            Pair<AbilityAnnotation, Class<?>> pair = findAbilityAnnotationAndAbilityClass(currentClass);
            if (null == pair) {
                continue;
            }
            AbilityAnnotation ability = pair.getLeft();
            Class<?> targetClass = pair.getRight();
            if (null == ability) {
                continue;
            }
            if (null != regDTO.getParent()) {
                if (!ability.getParent().equals(regDTO.getParent().getCode())) {
                    continue;
                }
            }
            // 缓存Ability
            AbilitySpec abilitySpec = AbilityCache.getInstance().doCacheAbilitySpec(ability, targetClass);
            abilitySpecList.add(abilitySpec);
            // 往能力中添加Ability实例（扩展点实现）
            // 这里是将所有ability spi实现的实例设置到每一个ability缓存？
            abilitySpec.addAbilityInstance(scanAbilityInstance(abilitySpec, regDTO.getClassSet()));

        }
        return abilitySpecList;
    }

    @SuppressWarnings("all")
    private Pair<AbilityAnnotation, Class<?>> findAbilityAnnotationAndAbilityClass(Class<?> currentClass) {

        List<AbilityAnnotationParser> parsers = LatticeAnnotationSpiFactory.getInstance().getAbilityAnnotationParsers();
        for (AbilityAnnotationParser parser : parsers) {
            Annotation annotation = currentClass.getDeclaredAnnotation(parser.getAnnotationClass());
            if (null == annotation) {
                continue;
            }
            AbilityAnnotation annotationInfo = parser.buildAnnotationInfo(annotation, currentClass);
            return Pair.of(annotationInfo, currentClass);
        }
        return null;
    }

    // 扫描能力实例
    @SuppressWarnings("all")
    public synchronized List<AbilityInstSpec> scanAbilityInstance(AbilitySpec abilitySpec, Collection<Class> classSet) {
        // 注册能力实例
        List<AbilityInstSpec> abilityInstanceSpecList = new ArrayList<>(registerAbilityInstances(abilitySpec, classSet));
        abilityInstanceSpecList.sort(Comparator.comparingInt(AbilityInstSpec::getPriority));
        return abilityInstanceSpecList;
    }

    @SuppressWarnings("all")
    private List<AbilityInstSpec> registerAbilityInstances(AbilitySpec abilitySpec, Collection<Class> classSet) {
        // fixme 这里为什么要传入所有的ability spi实现？
        // fixme 这里为什么要使用列表？
        List<AbilityInstSpec> instanceSpecs = new ArrayList<>();
        // 遍历所有ability spi实现
        for (Class<?> targetClass : classSet) {
            // 不能是抽象类 或 接口
            if (Modifier.isAbstract(targetClass.getModifiers())
                    || Modifier.isInterface(targetClass.getModifiers())) {
                continue;
            }
            // 必须是当前abilitySpec的子类
            if (!LatticeClassUtils.isSubClassOf(targetClass, abilitySpec.getAbilityClass())) {
                continue;
            }
            // 注册Ability实例
            AbilityInstBuildResult result = innerRegisterAbilityInstance(abilitySpec, targetClass);
            if (!result.isSuccess() && !result.isRegistered()) {
                Message message = null == result.getMessage() ? Message.code("LATTICE-CORE-RT-0001", targetClass.getName(), "not clear")
                        : result.getMessage();
                throw new LatticeRuntimeException(message);
            }
            AbilityInstSpec abilityInstanceSpec = result.getInstanceSpec();
            if (null != abilityInstanceSpec)
                instanceSpecs.add(abilityInstanceSpec);
        }

        return instanceSpecs;
    }

    private AbilityInstBuildResult innerRegisterAbilityInstance(AbilitySpec abilitySpec, Class<?> instanceClass) {
        IAbility<?> ability;
        IAbility<?> originAbility;
        // 通过Ability实现类，获取/创建 Class 的实例对象
        Object beanViaClass = getAndCreateSpringBeanViaClass(instanceClass, (Object) null);
        if (beanViaClass instanceof IAbility) {
            ability = (IAbility<?>) beanViaClass;
            if (AopUtils.isAopProxy(ability)) {
                Class<?> originCls = AopUtils.getTargetClass(ability);
                try {
                    Object curObject = getAndCreateSpringBeanViaClass(originCls, (Object) null);
                    originAbility = (IAbility<?>) curObject;
                } catch (Exception e) {
                    return AbilityInstBuildResult.failed(Message.code("LATTICE-CORE-RT-0002", originCls.getName()));
                }
            } else {
                originAbility = ability;
            }
        } else {
            return AbilityInstBuildResult.failed(Message.code("LATTICE-CORE-RT-0002", instanceClass.getName()));
        }

        // 判断Ability 实例是否已注册
        if (isAbilityInstanceRegistered(abilitySpec, originAbility)) {
            return AbilityInstBuildResult.registered();
        }

        // 构建Ability实例（包含扩展点）
        AbilityInstBuildResult result = buildAbilityInstanceSpec(abilitySpec, originAbility, instanceClass);
        if (result.isSuccess()) {
            AbilityInstSpec abilityInstanceSpec = result.getInstanceSpec();
            if (null != abilityInstanceSpec) {
                // 缓存Ability实例的扩展点
                Lattice.getInstance().getRuntimeCache()
                        .getExtensionCache()
                        .doCacheExtensionSpec(abilityInstanceSpec.getExtensions());
            }
        }
        return result;
    }

    private boolean isAbilityInstanceRegistered(AbilitySpec abilitySpec, IAbility<?> ability) {
        return abilitySpec.getAbilityInstSpecMap().values()
                .stream()
                .anyMatch(p -> p.getCode().equals(ability.getInstanceCode()));
    }

    private AbilityInstBuildResult buildAbilityInstanceSpec(
            AbilitySpec abilitySpec, IAbility<?> instance, Class<?> instanceClass) {
        try {
            AbilityInstSpec instanceDesc = new AbilityInstSpec();
            instanceDesc.setInstanceClass(instanceClass.getName());
            instanceDesc.setCode(instance.getInstanceCode());
            instanceDesc.setName(instanceClass.getSimpleName());
            // 生成Ability扩展点
            instanceDesc.getExtensions().addAll(scanAbilityExtensions(instance, abilitySpec));

            PriorityAnnotation annotation = getPriorityAnnotationInfo(instanceClass);
            if (null != annotation) {
                instanceDesc.setPriority(annotation.getValue());
            }

            // 添加单个实例
            abilitySpec.addAbilityInstance(instanceDesc);
            return AbilityInstBuildResult.success(instanceDesc);
        } catch (LatticeRuntimeException ex) {
            throw ex;
        } catch (Exception e) {
            return AbilityInstBuildResult.failed(Message.code("LATTICE-CORE-RT-0003", instanceClass, e.getMessage()));
        }
    }

    private Set<ExtensionSpec> scanAbilityExtensions(IAbility<?> ability, AbilitySpec abilitySpec) {
        try {
            // 默认扩展实现
            Class<?> returnType = ability.getDefaultRealization().getClass();
            if (returnType.isAnonymousClass() || returnType.isInterface()) {
                throw new LatticeRuntimeException("LATTICE-CORE-RT-0022", returnType.getName());
            }
            return new HashSet<>(scanAbilityExtensions(returnType, abilitySpec));
        } catch (LatticeRuntimeException ex) {
            throw ex;
        } catch (Throwable th) {
            Message message = Message.code("LATTICE-CORE-RT-0004", ability.getClass().getName(),
                    th.getMessage());
            log.error(message.getText(), th);
            throw new LatticeRuntimeException(message);
        }
    }

    private Set<ExtensionSpec> scanAbilityExtensions(Class<?> itfClass, AbilitySpec abilitySpec) {
        Set<ExtensionSpec> extensionSpecList = new HashSet<>();
        // 默认扩展实现Class的所有方法
        Method[] methods = itfClass.getMethods();
        for (Method method : methods) {
            // @Extension注解是可以被继承的
            ExtensionAnnotation annotation = getExtensionAnnotation(method);
            if (null == annotation) {
                continue;
            }
            // 构建扩展点
            ExtensionSpec extensionSpec = buildExtensionPointSpec(annotation, abilitySpec, itfClass, method);
            if (null != extensionSpec) {
                extensionSpecList.add(extensionSpec);
            }
        }

        return extensionSpecList;
    }

    public ExtensionSpec buildExtensionPointSpec(
            ExtensionAnnotation annotation,
            AbilitySpec abilitySpec, Class<?> itfClass, Method method) {

        if (null == annotation)
            return null;

        return buildExtensionPointSpec(abilitySpec, annotation.getCode(),
                annotation.getName(), annotation.getDesc(), itfClass, method,
                annotation.getReduceType(), annotation.getProtocolType());
    }

    private ExtensionSpec buildExtensionPointSpec(AbilitySpec abilitySpec, String extensionCode,
                                                  String extensionName,
                                                  String extensionDesc,
                                                  Class<?> itfClass, Method method,
                                                  ReduceType reduceType,
                                                  ProtocolType protocolType) {

        ExtensionSpec extensionSpec =
                ExtensionSpec.of(method, abilitySpec.getCode(),
                        extensionCode, extensionName, extensionDesc);
        extensionSpec.setReduceType(reduceType);
        extensionSpec.setProtocolType(protocolType);
        extensionSpec.setItfClass(itfClass);
        return extensionSpec;
    }
}
