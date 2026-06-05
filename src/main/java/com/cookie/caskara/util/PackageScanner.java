package com.cookie.caskara.util;

import com.cookie.caskara.Caskara;
import com.cookie.caskara.annotations.CaskaraEntity;

import java.io.File;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Utility to scan packages for @CaskaraEntity annotations.
 */
public class PackageScanner {

    public static void scanAndRegister(String packageName) {
        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            if (classLoader == null) {
                classLoader = PackageScanner.class.getClassLoader();
            }
            String path = packageName.replace('.', '/');
            Enumeration<URL> resources = classLoader.getResources(path);
            List<File> dirs = new ArrayList<>();
            List<URL> jars = new ArrayList<>();
            
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                if (resource.getProtocol().equals("file")) {
                    dirs.add(new File(URLDecoder.decode(resource.getFile(), "UTF-8")));
                } else if (resource.getProtocol().equals("jar")) {
                    jars.add(resource);
                }
            }
            
            List<Class<?>> classes = new ArrayList<>();
            for (File directory : dirs) {
                classes.addAll(findClasses(directory, packageName, classLoader));
            }
            for (URL jar : jars) {
                classes.addAll(findClassesInJar(jar, packageName, classLoader));
            }
            
            int count = 0;
            for (Class<?> clazz : classes) {
                if (clazz.isAnnotationPresent(CaskaraEntity.class)) {
                    Caskara.register(clazz);
                    count++;
                }
            }
            
            System.out.println("[Caskara] Scanned package '" + packageName + "' and registered " + count + " entities.");
            
        } catch (Exception e) {
            System.err.println("[Caskara] Failed to scan package '" + packageName + "': " + e.getMessage());
        }
    }

    private static List<Class<?>> findClasses(File directory, String packageName, ClassLoader classLoader) {
        List<Class<?>> classes = new ArrayList<>();
        if (!directory.exists()) {
            return classes;
        }
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    classes.addAll(findClasses(file, packageName + "." + file.getName(), classLoader));
                } else if (file.getName().endsWith(".class")) {
                    String className = packageName + '.' + file.getName().substring(0, file.getName().length() - 6);
                    try {
                        classes.add(Class.forName(className, false, classLoader));
                    } catch (ClassNotFoundException | NoClassDefFoundError ignored) {}
                }
            }
        }
        return classes;
    }

    private static List<Class<?>> findClassesInJar(URL jarUrl, String packageName, ClassLoader classLoader) {
        List<Class<?>> classes = new ArrayList<>();
        try {
            String path = jarUrl.getPath();
            int bangIndex = path.indexOf("!");
            String jarFilePath = bangIndex != -1 ? path.substring(5, bangIndex) : path.substring(5);
            try (JarFile jarFile = new JarFile(URLDecoder.decode(jarFilePath, "UTF-8"))) {
                Enumeration<JarEntry> entries = jarFile.entries();
                String packagePath = packageName.replace('.', '/');
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String entryName = entry.getName();
                    if (entryName.startsWith(packagePath) && entryName.endsWith(".class") && !entry.isDirectory()) {
                        String className = entryName.substring(0, entryName.length() - 6).replace('/', '.');
                        try {
                            classes.add(Class.forName(className, false, classLoader));
                        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
        return classes;
    }
}
