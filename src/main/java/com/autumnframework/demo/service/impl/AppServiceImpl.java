package com.autumnframework.demo.service.impl;

import com.autumnframework.annotation.AService;
import com.autumnframework.demo.service.AppService;

@AService
public class AppServiceImpl implements AppService {

    @Override
    public String helloWorld(String name, Integer age) {
        return name + age;
    }
}
