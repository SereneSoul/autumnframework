package com.autumnframework.demo.controller;

import com.autumnframework.annotation.AAutowired;
import com.autumnframework.annotation.AController;
import com.autumnframework.annotation.ARequestMapping;
import com.autumnframework.annotation.ARequestParam;
import com.autumnframework.demo.service.AppService;

@AController
@ARequestMapping("app")
public class AppController {
    
    @AAutowired
    private AppService appService;
    
    @ARequestMapping("hello")
    public String helloWorld(@ARequestParam("name") String name,@ARequestParam("age") Integer age){
        return appService.helloWorld(name, age);
    }
}
