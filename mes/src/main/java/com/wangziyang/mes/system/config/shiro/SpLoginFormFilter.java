package com.wangziyang.mes.system.config.shiro;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangziyang.mes.assistant.AssistantResponses;
import com.wangziyang.mes.common.Result;
import com.wangziyang.mes.common.util.HttpUtil;
import org.apache.shiro.web.filter.authc.FormAuthenticationFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;

/**
 * @author SongPeng
 * @date 2020/3/6
 */
public class SpLoginFormFilter extends FormAuthenticationFilter {

    private static final Logger logger = LoggerFactory.getLogger(SpLoginFormFilter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    protected boolean onAccessDenied(ServletRequest request, ServletResponse response) throws Exception {
        HttpServletRequest httpServletRequest = (HttpServletRequest) request;
        HttpServletResponse httpServletResponse = (HttpServletResponse) response;
        String assistantPath = httpServletRequest.getContextPath() + "/admin/assistant";
        String requestUri = httpServletRequest.getRequestURI();
        if (assistantPath.equals(requestUri) || requestUri.startsWith(assistantPath + "/")) {
            long started = System.nanoTime();
            String traceId = UUID.randomUUID().toString();
            String answer = "请先登录后使用助手。";
            Map<String, Object> data = AssistantResponses.reply("AUTH_REQUIRED", "rule-based", answer);
            long elapsedMs = (System.nanoTime() - started) / 1000000;
            data.put("traceId", traceId);
            data.put("elapsedMs", elapsedMs);
            data.put("modelConnected", false);
            httpServletResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            httpServletResponse.setContentType("application/json");
            httpServletResponse.setCharacterEncoding("UTF-8");
            JSON.writeValue(httpServletResponse.getWriter(), Result.failure(data, answer));
            logger.info("traceId={} endpoint=/admin/assistant action=authentication userId=- status=AUTH_REQUIRED elapsedMs={}",
                    traceId, elapsedMs);
            return false;
        }
        if (isLoginRequest(request, response)) {
            if (isLoginSubmission(request, response)) {
                if (logger.isTraceEnabled()) {
                    logger.trace("Login submission detected.  Attempting to execute login.");
                }
                return executeLogin(request, response);
            } else {
                if (logger.isTraceEnabled()) {
                    logger.trace("Login page view.");
                }
                //allow them to see the login page
                return true;
            }
        } else {
            if (logger.isTraceEnabled()) {
                logger.trace("Attempting to access a path which requires authentication.  Forwarding to the " +
                        "Authentication url [" + getLoginUrl() + "]");
            }
            //如果是Ajax请求，不跳转登录
            if (HttpUtil.isAjax(httpServletRequest)) {
                httpServletResponse.setStatus(401);
            } else {
                saveRequestAndRedirectToLogin(request, response);
            }
            return false;
        }
    }
}
