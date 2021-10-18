/*
 * Copyright 2021 OPPO ESA Stack Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.esastack.cabin.container.service.share;

import io.esastack.cabin.api.domain.Module;
import io.esastack.cabin.api.service.share.SharedClassService;
import io.esastack.cabin.common.exception.CabinRuntimeException;
import io.esastack.cabin.common.log.CabinLoggerFactory;
import io.esastack.cabin.common.util.CabinStringUtil;
import io.esastack.cabin.container.domain.LibModule;
import io.esastack.cabin.container.service.loader.LibModuleClassLoader;
import io.esastack.cabin.loader.jar.Handler;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Different modules could export same packages, but should not export same classes!
 */
public class SharedClassServiceImpl implements SharedClassService {

    private static final Logger LOGGER = CabinLoggerFactory.getLogger(SharedClassServiceImpl.class);

    private static final Object sentinel = new Object();

    private final AtomicBoolean preLoaded = new AtomicBoolean(false);

    private final ConcurrentMap<String, Class<?>> cachedClasses;

    private final ConcurrentMap<String, LibModule> classToModuleMap;

    private final Map<String, Map<LibModule, Object>> packageToModuleMap;

    public SharedClassServiceImpl() {
        this.cachedClasses = new ConcurrentHashMap<>();
        this.classToModuleMap = new ConcurrentHashMap<>();
        this.packageToModuleMap = new ConcurrentHashMap<>();
    }

    @Override
    public void addSharedPackage(final String packageName, final Module module) {
        if (CabinStringUtil.isBlank(packageName)) {
            return;
        }

        final Map<LibModule, Object> modules =
                packageToModuleMap.computeIfAbsent(packageName, name -> new ConcurrentHashMap<>());
        modules.put((LibModule) module, sentinel);
    }

    @Override
    public void preLoadAllSharedClasses() {
        if (preLoaded.compareAndSet(false, true)) {
            classToModuleMap.forEach((className, module) -> {
                final Class<?> clazz = getClassFromModule(className, module);
                //Only throw exception while the class is inner class
                if (clazz == null && !className.contains("$")) {
                    throw new CabinRuntimeException(String.format("Could not load class %s which is exported " +
                            "by module %s from it!", className, module.getName()));
                }
            });
        }
    }

    @Override
    public Class<?> getSharedClass(final String className) {
        Class<?> clazz = getCachedClass(className);
        if (clazz != null) {
            return clazz;
        }

        clazz = getClassFromModuleAndCache(className);
        if (clazz != null) {
            return clazz;
        }

        //Get the classes not scanned while package the lib modules
        int index = className.lastIndexOf(".");
        while (index > 0) {
            final String packageName = className.substring(0, index);
            final Map<LibModule, Object> modules = packageToModuleMap.get(packageName);
            if (modules != null && !modules.isEmpty()) {
                Class<?> prevLoadedClass = null;
                LibModule prevLoadedModule = null;
                for (LibModule libModule : modules.keySet()) {
                    clazz = getClassFromModule(className, libModule);
                    if (clazz != null) {
                        if (prevLoadedClass != null) {
                            throw new CabinRuntimeException(
                                    String.format("Class export conflicted, %s is exported by module %s and %s",
                                            className, prevLoadedModule.getName(), libModule.getName()));
                        } else {
                            prevLoadedClass = clazz;
                            prevLoadedModule = libModule;
                        }
                    }
                }
                if (prevLoadedClass != null) {
                    LOGGER.info("Trying to add class {} exported by Module {} to sharedClassService!",
                            className, prevLoadedModule.getName());
                    final Class<?> prevClazz = cachedClasses.putIfAbsent(className, prevLoadedClass);
                    if (prevClazz != null && prevClazz != prevLoadedClass) {
                        throw new CabinRuntimeException(
                                String.format("Class export conflicted, %s is exported by ClassLoader %s and %s",
                                        className, prevClazz.getClassLoader(), prevLoadedClass.getClassLoader()));
                    }
                    return prevLoadedClass;
                }
            }
            index = packageName.lastIndexOf(".");
        }
        return null;
    }

    @Override
    public void addSharedClass(final String className, final Class<?> clazz) {

    }

    @Override
    public void addSharedClass(final String className, final Module module) {
        if (className == null || module == null) {
            return;
        }
        final Module prevModule = classToModuleMap.putIfAbsent(className, (LibModule) module);
        if (prevModule != null && prevModule != module) {
            throw new CabinRuntimeException(String.format("Class export conflicted, %s is exported by module" +
                    " %s and %s", className, prevModule.getName(), module.getName()));
        }
    }

    @Override
    public Map<String, Class<?>> getSharedClassMap() {
        preLoadAllSharedClasses();
        return Collections.unmodifiableMap(cachedClasses);
    }

    @Override
    public int getSharedClassCount() {
        return classToModuleMap.size();
    }

    @Override
    public boolean containsClass(final String className) {
        return classToModuleMap.containsKey(className);
    }

    private Class<?> getCachedClass(final String className) {
        return cachedClasses.get(className);
    }

    private Class<?> getClassFromModuleAndCache(final String className) {
        final LibModule module = classToModuleMap.get(className);
        if (module != null) {
            final Class<?> result = getClassFromModule(className, module);
            if (result != null) {
                cachedClasses.put(className, result);
                return result;
            }
        }
        return null;
    }

    private Class<?> getClassFromModule(final String className, final LibModule module) {
        try {
            Handler.setUseFastConnectionExceptions(true);
            final LibModuleClassLoader libModuleClassLoader = (LibModuleClassLoader) module.getClassLoader();
            if (libModuleClassLoader != null) {
                return libModuleClassLoader.loadClassFromClasspath(className);
            }
        } catch (Throwable e) {
            //NOP
        } finally {
            Handler.setUseFastConnectionExceptions(false);
        }
        return null;
    }
}
