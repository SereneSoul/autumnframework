package com.autumnframework.servlet;

import com.autumnframework.annotation.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class AutumnDispatcherServlet extends HttpServlet {
    
    private static final long serialVersionUID = 1L;
    /**
     * 和web.xml中的param-name保持一致
     */
    private static final String LOCATION = "contextConfigLocation";
    /**
     * 保存配置信息
     */
    private Properties p = new Properties();
    /**
     * 保存被扫描到的相关类名
     */
    private List<String> classNames = new ArrayList<>();
    /**
     * 核心IOC容器，保存所有初始化的Bean
     */
    private Map<String, Object> ioc = new ConcurrentHashMap<>();
    /**
     * 保存所有url和方法的映射关系
     */
    private Map<String, Method> handlerMapping = new ConcurrentHashMap<>();
    
    public AutumnDispatcherServlet(){
        super();
    }
    
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        this.doPost(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            Object o = doDispatch(req, resp);
            if(o != null){
                resp.getWriter().write(o.toString());
            }
        }catch (Exception e){
            resp.getWriter().write("500 Exception,Details:\r\n" + Arrays.toString(e.getStackTrace())
            .replaceAll("\\[|\\]", "").replaceAll(",\\s", "\r\n"));
        }
    }

    

    @Override
    public void init(ServletConfig config) throws ServletException {
        doLoadConfig(config.getInitParameter(LOCATION));
        doScanner(p.getProperty("defaultScanPackage"));
        doInstance();
        doAutoWired();
        initHandlerMapping();
    }

    private Object doDispatch(HttpServletRequest req, HttpServletResponse resp) throws Exception{
        if(this.handlerMapping.isEmpty()){
            return null;
        }
        
        String url = req.getRequestURI();
        String contextPath = req.getContextPath();
        url = url.replace(contextPath, "").replaceAll("/+", "/");
        
        if(!this.handlerMapping.containsKey(url)){
            resp.getWriter().write("404 Not Found!!");
            return null;
        }
        
        Method method = this.handlerMapping.get(url);
        //获取方法的参数列表
        Class<?>[] parameterTypes = method.getParameterTypes();
        Annotation[][] params = method.getParameterAnnotations();
        //获取请求的参数
        Map<String, String[]> parameterMap = req.getParameterMap();
        //保存参数值
        Object[] paramValues = new Object[parameterTypes.length];
        //方法的参数列表
        for (int i = 0; i < parameterTypes.length; i++) {
            Class parameterType = parameterTypes[i];
            if (parameterType == HttpServletRequest.class){
                paramValues[i] = req;
                continue;
            }else if(parameterType == HttpServletResponse.class){
                paramValues[i] = resp;
                continue;
            }else {
                ARequestParam requestParam =  (ARequestParam) params[i][0];
                String paramName = requestParam.value().trim();
                for(Map.Entry<String, String[]> param : parameterMap.entrySet()){
                    if(StringUtils.equals(paramName, param.getKey())){
                        if(parameterType == String.class){
                            String value = Arrays.toString(param.getValue())
                                    .replaceAll("\\[|\\]", "")
                                    .replaceAll(",\\s", ",");
                            paramValues[i] = value;
                        }else if(parameterType == Integer.class){
                            Integer value = Integer.valueOf(Arrays.toString(param.getValue())
                                    .replaceAll("\\[|\\]", "")
                                    .replaceAll(",\\s", ","));
                            paramValues[i] = value;
                        }
                        
                    }
                }
            }
        }
        try {
            String beanName = lowerFirstCase(method.getDeclaringClass().getSimpleName());
            return method.invoke(this.ioc.get(beanName), paramValues);
        }catch (Exception e){
            log.error(e.getMessage());
        }
        return null;
    }
    /**
     * 构造HandlerMapping
     */
    private void initHandlerMapping() {
        if(ioc.isEmpty()){
            return;
        }
        for (Map.Entry<String, Object> entry : ioc.entrySet()){
            Class<?> clazz = entry.getValue().getClass();
            if(!clazz.isAnnotationPresent(AController.class)){
                continue;
            }
            String baseUrl = "";
            if(clazz.isAnnotationPresent(ARequestMapping.class)){
                ARequestMapping requestMapping = clazz.getAnnotation(ARequestMapping.class);
                baseUrl = requestMapping.value().trim();
            }
            
            Method [] methods = clazz.getMethods();
            for (Method method : methods){
                if(!method.isAnnotationPresent(ARequestMapping.class)){
                    continue;
                }
                ARequestMapping requestMapping = method.getAnnotation(ARequestMapping.class);
                String url = ("/" + baseUrl + "/" + requestMapping.value().trim()).replaceAll("/+", "/");
                handlerMapping.put(url, method);
            }
        }
    }

    /**
     * 依赖注入
     */
    private void doAutoWired() {
        if(ioc.isEmpty()){
            return;
        }
        for(Map.Entry<String, Object> entry : ioc.entrySet()){
            Field [] fields = entry.getValue().getClass().getDeclaredFields();
            for(Field field : fields){
                if(!field.isAnnotationPresent(AAutowired.class)){
                    return;
                }
                
                AAutowired autowired = field.getAnnotation(AAutowired.class);
                String beanName = autowired.value().trim();
                if(StringUtils.isBlank(beanName)){
                    beanName = lowerFirstCase(field.getType().getSimpleName());
                }
                field.setAccessible(true);
                try {
                    field.set(entry.getValue(), ioc.get(beanName));
                }catch (Exception e){
                    log.error(e.getMessage());
                }
            }
        }
    }

    /**
     * 初始化所有相关类的实例，并保存到IOC容器
     */
    private void doInstance() {
        if(classNames.size() == 0){
            return;
        }
        try {
            for(String className : classNames){
                Class<?> clazz = Class.forName(className);
                if(clazz.isAnnotationPresent(AController.class)){
                    AController controller = clazz.getAnnotation(AController.class);
                    String beanName = controller.value();
                    if(StringUtils.isBlank(beanName)){
                        beanName = lowerFirstCase(clazz.getSimpleName());
                    }
                    ioc.put(beanName, clazz.newInstance());
                }else if(clazz.isAnnotationPresent(AService.class)){
                    AService service = clazz.getAnnotation(AService.class);
                    String beanName = service.value();
                    if(StringUtils.isNotBlank(beanName)){
                        ioc.put(beanName, clazz.newInstance());
                        continue;
                    }
                    //key放入接口名称,value放入实现类
                    Class<?> [] interfaces = clazz.getInterfaces();
                    for(Class<?> i : interfaces){
                        ioc.put(lowerFirstCase(i.getSimpleName()), clazz.newInstance());
                    }
                }else {
                    continue;
                }
            }
        }catch (Exception e){
            log.error(e.getMessage());
        }
    }

    /**
     * 扫描所有相关类
     * @param packageName
     */
    private void doScanner(String packageName) {
        URL url = this.getClass().getClassLoader().getResource("/" + packageName.replaceAll("\\.", "/"));
        File dir = new File(url.getFile());
        for (File file : dir.listFiles()){
            if(file.isDirectory()){
                doScanner(packageName + "." + file.getName());
            }else{
                classNames.add(packageName + "." + file.getName().replace(".class", "").trim());
            }
        }
    }

    /**
     * 加载配置文件
     * @param location
     */
    private void doLoadConfig(String location) {
        InputStream fis = null;
        try {
            fis = this.getClass().getClassLoader().getResourceAsStream(location);
            p.load(fis);
        }catch (Exception e){
            log.error(e.getMessage());
        }finally {
            try {
                if (fis != null){
                    fis.close();
                }
            }catch (IOException e){
                log.error(e.getMessage());
            }
        }
    }
    
    private String lowerFirstCase(String str){
        char [] chars = str.toCharArray();
        chars[0] += 32;
        return String.valueOf(chars);
    }
}
