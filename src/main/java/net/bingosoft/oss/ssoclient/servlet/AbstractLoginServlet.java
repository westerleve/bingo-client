package net.bingosoft.oss.ssoclient.servlet;

import net.bingosoft.oss.ssoclient.SSOClient;
import net.bingosoft.oss.ssoclient.internal.Strings;
import net.bingosoft.oss.ssoclient.internal.Urls;
import net.bingosoft.oss.ssoclient.model.AccessToken;
import net.bingosoft.oss.ssoclient.model.Authentication;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.UUID;

/**
 * @since 3.0.1
 */
public abstract class AbstractLoginServlet extends HttpServlet{

    protected static final String ID_TOKEN_PARAM                 = "id_token";
    protected static final String AUTHZ_CODE_PARAM               = "code";

    private SSOClient client;

    @Override
    public void init(ServletConfig config) throws ServletException {
        this.client = getClient(config);
        super.init(config);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if(isRedirectedFromSSO(req)){
            gotoLocalLogin(req,resp);
        }else {
            redirectToSSOLogin(req,resp);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doGet(req,resp);
    }

    protected void redirectToSSOLogin(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String redirectUri = buildRedirectUri(req,resp);
        String loginUrl = buildLoginUrl(req,resp,redirectUri);

        resp.sendRedirect(loginUrl);

    }

    protected void gotoLocalLogin(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        if(checkOauth2LoginState(req,resp)){
            String idToken = req.getParameter(ID_TOKEN_PARAM);
            String code = req.getParameter(AUTHZ_CODE_PARAM);

            Authentication authc = client.verifyIdToken(idToken);
            AccessToken token = client.obtainAccessTokenByCode(code);

            localLogin(req,resp,authc,token);

            String returnUrl = req.getParameter("return_url");
            if(Strings.isEmpty(returnUrl)){
                returnUrl = Urls.getServerBaseUrl(req)+getContextPathOfReverseProxy(req);
                if(returnUrl.endsWith("//")){
                    returnUrl.substring(0,returnUrl.length()-1);
                }
            }
            resp.sendRedirect(returnUrl);
        }else {
            resp.sendError(HttpURLConnection.HTTP_BAD_REQUEST,"state has been change!");
        }
    }

    /**
     * OAuth????????????????????????<code>state</code>?????????????????????
     *
     * ?????????????????????<code>state</code>????????????SSO???????????????????????????????????????????????????????????????????????????????????????
     *
     * ??????????????????<code>true</code>??????????????????false???
     *
     * @see #setOauth2LoginState(HttpServletRequest, HttpServletResponse, String)
     */
    protected boolean checkOauth2LoginState(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String state = req.getParameter("state");
        String sessionState = (String) req.getSession().getAttribute("oauth2_login_state");
        if(!Strings.equals(sessionState,state)){
            return false;
        }
        return true;
    }

    /**
     * ?????????????????????????????????<code>state</code>?????????
     *
     * ????????????????????????{@link UUID},?????????'-'????????????<code>state</code>???
     *
     * @see #checkOauth2LoginState(HttpServletRequest, HttpServletResponse)
     */
    protected String setOauth2LoginState(HttpServletRequest req, HttpServletResponse resp, String authzEndpoint){
        String state = UUID.randomUUID().toString().replace("-","");
        req.getSession().setAttribute("oauth2_login_state", state);
        authzEndpoint = Urls.appendQueryString(authzEndpoint,"state",state);
        return authzEndpoint;
    }

    protected String buildLoginUrl(HttpServletRequest req, HttpServletResponse resp, String redirectUri) {
        String authzEndpoint = client.getConfig().getAuthorizationEndpointUrl();
        authzEndpoint = Urls.appendQueryString(authzEndpoint,"response_type","code id_token");
        authzEndpoint = Urls.appendQueryString(authzEndpoint,"client_id",client.getConfig().getClientId());
        authzEndpoint = Urls.appendQueryString(authzEndpoint,"redirect_uri",redirectUri);
        if(!Strings.isEmpty(client.getConfig().getLogoutUri())){
            authzEndpoint = Urls.appendQueryString(authzEndpoint,"logout_uri",client.getConfig().getLogoutUri());
        }
        if(!Strings.isEmpty(req.getParameter("login_token"))){
            authzEndpoint = Urls.appendQueryString(authzEndpoint,"login_token",req.getParameter("login_token"));
        }
        authzEndpoint = setOauth2LoginState(req,resp,authzEndpoint);
        return authzEndpoint;
    }

    /**
     * ??????SSO????????????????????????url??????????????????????????????SSO????????????????????????????????????uri????????????SSO????????????
     * ?????????????????????url????????????????????????
     *
     * <pre>
     *     http(s)://${domain}:${port}/${contextPath}/ssoclient/login?${queryString}
     *     ?????????
     *     http://www.example.com:80/demo/ssoclient/login?name=admin
     * </pre>
     *
     * ???????????????????????????client?????????????????????????????????(redirect_uri)?????????????????????????????????url????????????????????????????????????
     *
     * ???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
     */
    protected String buildRedirectUri(HttpServletRequest req, HttpServletResponse resp){
        String baseUrl = Urls.getServerBaseUrl(req);

        String requestUri = parseRequestUriWithoutContextPath(req);
        String current = baseUrl + getContextPathOfReverseProxy(req) + requestUri;

        String queryString = req.getQueryString();
        if(Strings.isEmpty(queryString)){
            if(client.getConfig().getDefaultReturnUrl() != null && !client.getConfig().getDefaultReturnUrl().isEmpty()){
                current = Urls.appendQueryString(current,"return_url",client.getConfig().getDefaultReturnUrl());
            }
            return current;
        }else {
            current = current+"?"+queryString;
            if(!Urls.parseQueryString(current).containsKey("return_url")){
                if(client.getConfig().getDefaultReturnUrl() != null && !client.getConfig().getDefaultReturnUrl().isEmpty()){
                    current = Urls.appendQueryString(current,"return_url",client.getConfig().getDefaultReturnUrl());
                }
            }
            return current;
        }
    }

    /**
     * ?????????????????????contextPath?????????????????????:<code>/ssoclient/login</code>
     */
    protected String parseRequestUriWithoutContextPath(HttpServletRequest req){
        String requestUri = req.getRequestURI();
        String contextPath = req.getContextPath();
        requestUri = requestUri.substring(contextPath.length());
        if(requestUri.startsWith("/")){
            return requestUri;
        }else {
            return "/"+requestUri;
        }
    }

    /**
     * ?????????????????????uri?????????????????????<code>req.getContextPath()</code>
     * ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
     *
     * ?????????????????????contextPath
     *
     * ?????????
     * <pre>
     *     ????????????????????????return req.getContextPath()
     *     ????????????????????? return "/proxyPath"
     * </pre>
     *
     */
    protected String getContextPathOfReverseProxy(HttpServletRequest req){
        return req.getContextPath();
    }

    protected boolean isRedirectedFromSSO(HttpServletRequest req){
        String idToken = req.getParameter(ID_TOKEN_PARAM);
        String accessToken = req.getParameter(AUTHZ_CODE_PARAM);
        return !Strings.isEmpty(idToken) && !Strings.isEmpty(accessToken);
    }



    /**
     * ????????????{@link SSOClient}??????
     */
    protected abstract SSOClient getClient(ServletConfig config) throws ServletException ;

    /**
     * ?????????SSO????????????????????????????????????
     */
    protected abstract void localLogin(HttpServletRequest req, HttpServletResponse resp, Authentication authc, AccessToken token) throws ServletException, IOException ;
}
