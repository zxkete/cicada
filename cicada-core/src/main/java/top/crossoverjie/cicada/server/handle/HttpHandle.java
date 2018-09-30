package top.crossoverjie.cicada.server.handle;

import com.alibaba.fastjson.JSON;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.*;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import top.crossoverjie.cicada.server.action.WorkAction;
import top.crossoverjie.cicada.server.action.param.Param;
import top.crossoverjie.cicada.server.action.param.ParamMap;
import top.crossoverjie.cicada.server.action.res.WorkRes;
import top.crossoverjie.cicada.server.config.AppConfig;
import top.crossoverjie.cicada.server.enums.StatusEnum;
import top.crossoverjie.cicada.server.exception.CicadaException;
import top.crossoverjie.cicada.server.intercept.CicadaInterceptor;
import top.crossoverjie.cicada.server.util.ClassScanner;
import top.crossoverjie.cicada.server.util.LoggerBuilder;
import top.crossoverjie.cicada.server.util.PathUtil;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Function:
 *
 * @author crossoverJie
 *         Date: 2018/8/30 18:47
 * @since JDK 1.8
 */
public class HttpHandle extends ChannelInboundHandlerAdapter {

    private final static Logger LOGGER = LoggerBuilder.getLogger(HttpHandle.class);

    private static final HttpDataFactory factory =
            new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE);

    private HttpPostRequestDecoder decoder;

    static {
        DiskFileUpload.deleteOnExitTemporaryFile = true; //should delete file on exit

        DiskFileUpload.baseDirectory = null;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {

        if (msg instanceof DefaultHttpRequest) {
            DefaultHttpRequest request = (DefaultHttpRequest) msg;


            // interceptor cache
            List<CicadaInterceptor> interceptors = new ArrayList<>() ;

            // request uri
            String uri = request.uri();
            QueryStringDecoder queryStringDecoder = new QueryStringDecoder(URLDecoder.decode(request.uri(), "utf-8"));

            //is file upload
            if(isUpload(queryStringDecoder,request)) return;

            // check Root Path
            AppConfig appConfig = checkRootPath(uri, queryStringDecoder);

            // route Action
            Class<?> actionClazz = routeAction(queryStringDecoder, appConfig);

            //build paramMap
            Param paramMap = buildParamMap(queryStringDecoder);


            //interceptor before
            interceptorBefore(interceptors, appConfig, paramMap);

            // execute Method
            WorkAction action = (WorkAction) actionClazz.newInstance();
            WorkRes execute = action.execute(paramMap);


            // interceptor after
            interceptorAfter(interceptors, paramMap);

            // Response
            responseMsg(ctx, execute);

        }else if(msg instanceof DefaultHttpContent){
            LOGGER.info("upload start------------------------------------------");
            parseFileData((DefaultHttpContent) msg,ctx);
        }

    }

    private boolean isUpload(QueryStringDecoder queryStringDecoder,DefaultHttpRequest request){

        String actionPath = PathUtil.getActionPath(queryStringDecoder.path());

        if(actionPath.startsWith("upload") && request.method().equals(HttpMethod.POST)){

            decoder = new HttpPostRequestDecoder(factory, request);

            return true;
        }

        return false;
    }


    private void parseFileData(DefaultHttpContent content,ChannelHandlerContext ctx){
        if(decoder!=null){

            WorkRes workRes = new WorkRes() ;
            workRes.setCode(String.valueOf(HttpResponseStatus.NOT_FOUND.code()));
            workRes.setMessage(HttpResponseStatus.NOT_FOUND.toString());

            decoder.offer(content);

            readHttpDataChunk();

            if(content instanceof DefaultLastHttpContent){
                workRes.setCode(String.valueOf(HttpResponseStatus.OK.code()));
                workRes.setMessage(HttpResponseStatus.OK.toString());
                responseMsg(ctx, workRes);
                reset();
            }
        }
    }

    private void reset(){
        decoder.destroy();
        decoder = null;
    }

    /**
     * Example of reading request by chunk and getting values from chunk to chunk
     */
    private void readHttpDataChunk(){

        try {
            while (decoder.hasNext()){

                LOGGER.info("read data------------------------------------------");
                InterfaceHttpData data = decoder.next();

                if(data!=null){
                    try {
                        writeHttpData(data);
                    } finally {
                        data.release();
                    }
                }
            }
        } catch (HttpPostRequestDecoder.EndOfDataDecoderException e) {
            //end
            e.printStackTrace();
        }

    }


    private void writeHttpData(InterfaceHttpData data){

        if(data.getHttpDataType() == InterfaceHttpData.HttpDataType.FileUpload){
            FileUpload fileUpload = (FileUpload) data;
            if (fileUpload.isCompleted()) {

                String tempDir = System.getProperty("user.home") + File.separator+ "upload" + File.separator;
                File dir = new File(tempDir);

                if(!dir.exists()){
                    dir.mkdir();
                }
                File dest = new File(dir, fileUpload.getFilename());

                LOGGER.info("file name :"+dest.getName());

                try {
                    fileUpload.renameTo(dest);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

        }
    }

    /**
     * interceptor after
     * @param interceptors
     * @param paramMap
     */
    private void interceptorAfter(List<CicadaInterceptor> interceptors, Param paramMap) {
        for (CicadaInterceptor interceptor : interceptors) {
            interceptor.after(paramMap);
        }
    }

    /**
     * interceptor before
     * @param interceptors
     * @param appConfig
     * @param paramMap
     * @throws Exception
     */
    private void interceptorBefore(List<CicadaInterceptor> interceptors, AppConfig appConfig, Param paramMap) throws Exception {
        Map<String, Class<?>> cicadaInterceptor = ClassScanner.getCicadaInterceptor(appConfig.getRootPackageName());
        for (Map.Entry<String, Class<?>> classEntry : cicadaInterceptor.entrySet()) {
            Class<?> interceptorClass = classEntry.getValue();
            CicadaInterceptor interceptor = (CicadaInterceptor) interceptorClass.newInstance();
            interceptor.before(paramMap) ;

            //add cache
            interceptors.add(interceptor);
        }
    }

    /**
     * Response
     * @param ctx
     * @param execute
     */
    private void responseMsg(ChannelHandlerContext ctx, WorkRes execute) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.copiedBuffer(JSON.toJSONString(execute), CharsetUtil.UTF_8));
        buildHeader(response);
        ctx.writeAndFlush(response);
    }

    /**
     * build paramMap
     * @param queryStringDecoder
     * @return
     */
    private Param buildParamMap(QueryStringDecoder queryStringDecoder) {
        Map<String, List<String>> parameters = queryStringDecoder.parameters();
        Param paramMap = new ParamMap() ;
        for (Map.Entry<String, List<String>> stringListEntry : parameters.entrySet()) {
            String key = stringListEntry.getKey();
            List<String> value = stringListEntry.getValue();
            paramMap.put(key,value.get(0)) ;
        }
        return paramMap;
    }

    /**
     * route Action
     * @param queryStringDecoder
     * @param appConfig
     * @return
     * @throws Exception
     */
    private Class<?> routeAction(QueryStringDecoder queryStringDecoder, AppConfig appConfig) throws Exception {
        String actionPath = PathUtil.getActionPath(queryStringDecoder.path());
        Map<String, Class<?>> cicadaAction = ClassScanner.getCicadaAction(appConfig.getRootPackageName());

        if (cicadaAction == null){
            throw new CicadaException("Must be configured WorkAction Object") ;
        }

        Class<?> actionClazz = cicadaAction.get(actionPath);
        if (actionClazz == null){
            throw new CicadaException(StatusEnum.REQUEST_ERROR,actionPath + " Not Fount") ;
        }
        return actionClazz;
    }

    /**
     * check Root Path
     * @param uri
     * @param queryStringDecoder
     * @return
     */
    private AppConfig checkRootPath(String uri, QueryStringDecoder queryStringDecoder) {
        AppConfig appConfig = AppConfig.getInstance();
        if (!PathUtil.getRootPath(queryStringDecoder.path()).equals(appConfig.getRootPath())){
            throw new CicadaException(StatusEnum.REQUEST_ERROR,uri) ;
        }
        return appConfig;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {

        WorkRes workRes = new WorkRes() ;
        workRes.setCode(String.valueOf(HttpResponseStatus.NOT_FOUND.code()));
        workRes.setMessage(cause.getMessage());

        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND, Unpooled.copiedBuffer(JSON.toJSONString(workRes), CharsetUtil.UTF_8)) ;
        buildHeader(response);
        ctx.writeAndFlush(response);
    }

    /**
     * build Header
     * @param response
     */
    private void buildHeader(DefaultFullHttpResponse response) {
        HttpHeaders headers = response.headers();
        headers.setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        headers.set(HttpHeaderNames.CONTENT_TYPE, "application/json");
    }
}
