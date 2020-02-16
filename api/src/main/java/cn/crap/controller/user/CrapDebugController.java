package cn.crap.controller.user;

import cn.crap.adapter.DebugAdapter;
import cn.crap.adapter.InterfaceAdapter;
import cn.crap.dto.DebugDto;
import cn.crap.dto.DebugInterfaceParamDto;
import cn.crap.dto.LoginInfoDto;
import cn.crap.enu.MyError;
import cn.crap.enu.ProjectStatus;
import cn.crap.enu.ProjectType;
import cn.crap.framework.JsonResult;
import cn.crap.framework.MyException;
import cn.crap.framework.base.BaseController;
import cn.crap.framework.interceptor.AuthPassport;
import cn.crap.model.InterfaceWithBLOBs;
import cn.crap.model.ModulePO;
import cn.crap.model.ProjectPO;
import cn.crap.query.InterfaceQuery;
import cn.crap.query.ModuleQuery;
import cn.crap.service.InterfaceService;
import cn.crap.service.ModuleService;
import cn.crap.service.ProjectService;
import cn.crap.utils.LoginUserHelper;
import cn.crap.utils.MD5;
import cn.crap.utils.MyString;
import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.*;
import java.util.stream.Collectors;

/**
 * TODO 待解决问题：路径参数问题
 */
@Controller
@RequestMapping("/user/crapDebug")
public class CrapDebugController extends BaseController {
    protected Logger log = Logger.getLogger(getClass());

    @Autowired
    private InterfaceService interfaceService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ModuleService moduleService;

    @RequestMapping("/synch.do")
    @ResponseBody
    @AuthPassport
    @Transactional
    public JsonResult synch(@RequestBody String body) throws Exception {
        List<DebugInterfaceParamDto> list = JSON.parseArray(body, DebugInterfaceParamDto.class);
        LoginInfoDto user = LoginUserHelper.getUser();
        String userId = user.getId();

        /**
         * 1. 处理项目
         * 项目根据用户ID生成，所以一定是该用户的
         * TODO 后续要支持多项目切换：如果项目ID存在，且用户为当前用户则可直接使用，否者新建项目（使用当前项目名称）
         * 调试项目ID唯一，根据用户ID生成，不在CrapApi网站显示
         */
        String projectId = generateProjectId(user);
        ProjectPO project = projectService.get(projectId);
        if (project == null) {
            project = buildProject(user, projectId);
            projectService.insert(project);
        }

        /**
         * 2.处理模块+接口
         */
        long moduleSequence = System.currentTimeMillis();
        for (DebugInterfaceParamDto debutModuleDTO : list) {

            /**
             * 2.1 模块处理
             */
            String moduleUniKey = debutModuleDTO.getModuleUniKey() == null ? debutModuleDTO.getModuleId() : debutModuleDTO.getModuleUniKey();
            if (debutModuleDTO == null || MyString.isEmpty(moduleUniKey)) {
                log.error("sync moduleUniKey is null:" + userId + ",moduleName:" + debutModuleDTO.getModuleName());
                continue;
            }

            ModulePO module = moduleService.getByUniKey(projectId, moduleUniKey);
            log.error("sync moduleUniKey:" + moduleUniKey);

            // 处理模块：删除、更新、添加，处理异常
            module = handelModule(user, project, module, moduleSequence, debutModuleDTO);
            moduleSequence = moduleSequence - 1;
            if (module == null){
                continue;
            }

            // 先删除需要删除的接口
            deleteDebug(module, debutModuleDTO);

            // 每个用户的最大接口数量不能超过100
            int totalNum = interfaceService.count(new InterfaceQuery().setProjectId(projectId));
            if (totalNum > 100) {
                return new JsonResult(MyError.E000058);
            }

            // 更新接口
            addDebug(projectId, module, user, debutModuleDTO, totalNum);
        }


        /**
         *  组装返回数据
         *  id 全部使用uniKey替代
         */
        List<ModulePO> modules = moduleService.select(new ModuleQuery().setProjectId(projectId).setPageSize(100));
        Map<String, ModulePO> moduleMap = modules.stream().collect(Collectors.toMap(ModulePO::getId, a -> a,(k1, k2)->k1));

        List<InterfaceWithBLOBs> debugs = interfaceService.queryAll(new InterfaceQuery().setProjectId(projectId));
        Map<String, List<DebugDto>> mapDebugs = new HashMap<>();
        for (InterfaceWithBLOBs d : debugs) {
            List<DebugDto> moduleDebugs = mapDebugs.get(d.getModuleId());
            if (moduleDebugs == null) {
                moduleDebugs = new ArrayList<>();
                mapDebugs.put(d.getModuleId(), moduleDebugs);
            }
            DebugDto dtoFromInterface = DebugAdapter.getDtoFromInterface(project, moduleMap, d);
            if (dtoFromInterface == null){
                continue;
            }
            moduleDebugs.add(dtoFromInterface);
        }

        List<DebugInterfaceParamDto> returnlist = new ArrayList<DebugInterfaceParamDto>();
        for (ModulePO m : modules) {
            try {
                DebugInterfaceParamDto debugDto = new DebugInterfaceParamDto();
                debugDto.setModuleId(m.getId());
                debugDto.setModuleName(m.getName());
                debugDto.setVersion(m.getVersionNum());
                debugDto.setStatus(m.getStatus());
                debugDto.setDebugs(mapDebugs.get(m.getId()) == null ? new ArrayList<>() : mapDebugs.get(m.getId()));
                returnlist.add(debugDto);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return new JsonResult(1, returnlist);
    }

    private String generateProjectId(LoginInfoDto user) {
        return MD5.encrytMD5(user.getId(), "").substring(0, 20) + "-debug";
    }

    private void addDebug(String projectId, ModulePO module, LoginInfoDto user, DebugInterfaceParamDto moduleDTO, int totalNum) {
        Assert.notNull(module, "deleteDebug module is null");
        if (moduleDTO.getStatus() == -1) {
            return;
        }

        String moduleId = module.getId();
        long debugSequence = System.currentTimeMillis();
        for (DebugDto debug : moduleDTO.getDebugs()) {
            debugSequence = debugSequence - 1;
            debug.setSequence(debugSequence);
            try {
                if (MyString.isEmpty(debug.getId())) {
                    log.error("addDebug debugId is null, moduleId:" + module.getId());
                    continue;
                }

                if (debug.getStatus() == -1) {
                    continue;
                }

                String uniKey = debug.getUniKey() == null ? debug.getId() : debug.getUniKey();
                log.error("addDebug name:" + debug.getName() + ",uniKey" + uniKey);

                InterfaceWithBLOBs old = interfaceService.getByUniKey(moduleId, uniKey);
                if (old != null){
                    if (old.getVersionNum() >= debug.getVersion()){
                        log.error("addDebug ignore error name:" + debug.getName());
                        continue;
                    }

                    debug.setStatus(old.getStatus());
                    debug.setUid(user.getId());
                    interfaceService.update(DebugAdapter.getInterfaceByDebug(module, old, debug));
                    continue;
                }
                old = InterfaceAdapter.getInit();
                old.setProjectId(projectId);
                old.setModuleId(moduleId);
                old.setUniKey(uniKey);

                interfaceService.insert(DebugAdapter.getInterfaceByDebug(module, old, debug));
                totalNum = totalNum + 1;
            } catch (Exception e) {
                e.printStackTrace();
                continue;
            }
        }
    }


    private void deleteDebug(ModulePO module, DebugInterfaceParamDto moduleDTO) {
        Assert.notNull(module, "deleteDebug module is null");
        if (moduleDTO.getStatus() == -1) {
            return;
        }

        String moduleId = module.getId();
        List<String> uniKeyList = Lists.newArrayList();
        for (DebugDto debug : moduleDTO.getDebugs()) {
            if (MyString.isEmpty(debug.getId()) && MyString.isEmpty(debug.getUniKey())) {
                log.error("deleteDebug error debugId is null:" + debug.getName());
                continue;
            }

            if (debug.getStatus() == -1) {
                uniKeyList.add(debug.getUniKey() == null ? debug.getId() : debug.getUniKey());
            }
        }
        interfaceService.deleteByModuleId(moduleId, uniKeyList);
    }

    private ModulePO handelModule(LoginInfoDto user, ProjectPO project, ModulePO module, long moduleSequence, DebugInterfaceParamDto moduleDTO) throws Exception{

        // 新增模块
        if (module == null && moduleDTO.getStatus() != -1) {
            module = buildModule(user, project, moduleSequence, moduleDTO);
            moduleService.insert(module);
        }

        // 删除模块
        else if (moduleDTO.getStatus() == -1 && module != null) {
            try {
                interfaceService.deleteByModuleId(module.getId());
                moduleService.delete(module.getId());
            } catch (MyException e){
                log.error("crapDebugController delete module fail:" + e.getErrorCode());
            }
        }

        // 更新模块
        else if (module != null && (moduleDTO.getVersion() == null || module.getVersionNum() <= moduleDTO.getVersion())) {
            module.setVersionNum(moduleDTO.getVersion() == null ? 0 : moduleDTO.getVersion());
            module.setName(moduleDTO.getModuleName());
            module.setSequence(moduleSequence);
            moduleService.update(module);
        }

        return module;
    }

    private ModulePO buildModule(LoginInfoDto user, ProjectPO project, long moduleSequence, DebugInterfaceParamDto d) {
        ModulePO module = new ModulePO();
        module.setName(d.getModuleName());
        module.setCreateTime(new Date());
        module.setSequence(moduleSequence);
        module.setProjectId(project.getId());
        module.setUserId(user.getId());
        module.setRemark("");
        module.setUrl("");
        module.setCategory("");
        module.setUniKey(d.getModuleUniKey() == null ? d.getModuleId() : d.getModuleUniKey());
        module.setVersionNum(d.getVersion() == null ? 0 : d.getVersion());
        return module;
    }

    private ProjectPO buildProject(LoginInfoDto user, String projectId) {
        ProjectPO project;
        project = new ProjectPO();
        project.setId(projectId);
        project.setCover("/resources/images/logo_new.png");
        project.setLuceneSearch(Byte.valueOf("0"));
        project.setName("默认调试项目");
        project.setStatus(ProjectStatus.COMMON.getStatus());
        project.setSequence(System.currentTimeMillis());
        project.setType(ProjectType.PRIVATE.getByteType());
        project.setUserId(user.getId());
        project.setCreateTime(new Date());
        project.setRemark("该项目是系统自动创建的PostWoman/ApiDebug插件项目，请勿删除！！！！");
        return project;
    }

}
