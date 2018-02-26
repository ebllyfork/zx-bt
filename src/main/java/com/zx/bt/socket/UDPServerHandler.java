package com.zx.bt.socket;

import com.zx.bt.config.Config;
import com.zx.bt.dto.AnnouncePeer;
import com.zx.bt.dto.FindNode;
import com.zx.bt.dto.MessageInfo;
import com.zx.bt.entity.Node;
import com.zx.bt.entity.InfoHash;
import com.zx.bt.enums.InfoHashTypeEnum;
import com.zx.bt.enums.NodeRankEnum;
import com.zx.bt.enums.YEnum;
import com.zx.bt.exception.BTException;
import com.zx.bt.repository.InfoHashRepository;
import com.zx.bt.repository.NodeRepository;
import com.zx.bt.store.RoutingTable;
import com.zx.bt.util.BTUtil;
import com.zx.bt.util.Bencode;
import com.zx.bt.util.CodeUtil;
import com.zx.bt.util.SendUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * author:ZhengXing
 * datetime:2018-02-13 12:26
 * dht服务端处理类
 */
@Slf4j
@Component
@ChannelHandler.Sharable
public class UDPServerHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    private static final String LOG = "[DHT服务端处理类]-";

    private final Bencode bencode;
    private final Config config;
    private final InfoHashRepository infoHashRepository;
    private final NodeRepository nodeRepository;
    private final RoutingTable routingTable;

    public UDPServerHandler(Bencode bencode, Config config,InfoHashRepository infoHashRepository,
                            NodeRepository nodeRepository, RoutingTable routingTable) {
        this.bencode = bencode;
        this.config = config;
        this.infoHashRepository = infoHashRepository;
        this.nodeRepository = nodeRepository;
        this.routingTable = routingTable;
    }


    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.info("{}通道激活", LOG);
        //给发送器工具类的channel赋值
        SendUtil.setChannel(ctx.channel());
    }


    /**
     * 接收到消息
     */
    @Override
    protected void messageReceived(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
        byte[] bytes = getBytes(packet);
        InetSocketAddress sender = packet.sender();
        //解码为map
        Map<String, Object> map;
        try {
            map = bencode.decode(bytes,Map.class);
        } catch (Exception e) {
            log.error("{}消息解码异常.发送者:{}.异常:{}", LOG, sender, e.getMessage(), e);
            return;
        }

        //解析出MessageInfo
        MessageInfo messageInfo;
        try {
            messageInfo = BTUtil.getMessageInfo(map);
        } catch (BTException e) {
            log.error("{}解析MessageInfo异常.异常:{}", LOG, e.getMessage());
            return;
        } catch (Exception e) {
            log.error("{}解析MessageInfo异常.异常:{}", LOG, e.getMessage(),e);
            return;
        }

        //如果发生异常
        if (messageInfo.getStatus().equals(YEnum.ERROR)) {
            log.error("{}对方节点:{},回复异常信息:{}",LOG,sender,map);
            return;
        }


        switch (messageInfo.getMethod()) {
            case PING:
                //如果是请求,进行回复
                if (messageInfo.getStatus().equals(YEnum.QUERY)) {
                    log.info("{}PING.发送者:{}", LOG, sender);
                    SendUtil.pingReceive(sender, config.getMain().getNodeId(), messageInfo.getMessageId());
                    break;
                }
                //如果是回复

                break;
            case FIND_NODE:
                //如果是请求
                if (messageInfo.getStatus().equals(YEnum.QUERY)) {

                    //截取出要查找的目标nodeId和 请求发送方nodeId
                    Map<String, Object> aMap = BTUtil.getParamMap(map, "a", "FIND_NODE,找不到a参数.map:" + map);
                    byte[] targetNodeId = BTUtil.getParamString(aMap, "target", "FIND_NODE,找不到target参数.map:" + map)
                            .getBytes(CharsetUtil.ISO_8859_1);
                    String id = CodeUtil.bytes2HexStr(BTUtil.getParamString(aMap, "id", "FIND_NODE,找不到id参数.map:" + map)
                            .getBytes(CharsetUtil.ISO_8859_1));
                    //查找
                    List<Node> nodes = routingTable.getForTop8(targetNodeId);
                    log.info("{}FIND_NODE.发送者:{},返回的nodes:{}", LOG, sender,nodes);
                    SendUtil.findNodeReceive(messageInfo.getMessageId(),sender,config.getMain().getNodeId(),nodes);
                    //操作路由表
                    routingTable.put(new Node(id, BTUtil.getIpBySender(sender), sender.getPort(), NodeRankEnum.FIND_NODE.getCode()));
                    break;
                }
                //如果是回复

                //回复主体
                Map<String, Object> rMap = BTUtil.getParamMap(map, "r", "FIND_NODE,找不到r参数.map:" + map);
                String id = CodeUtil.bytes2HexStr(BTUtil.getParamString(rMap, "id", "FIND_NODE,找不到id参数.map:" + map).getBytes(CharsetUtil.ISO_8859_1));
                byte[] nodesBytes = BTUtil.getParamString(rMap, "nodes", "FIND_NODE,找不到nodes参数.map:" + map).getBytes(CharsetUtil.ISO_8859_1);
                List<Node> nodeList = new LinkedList<>();
                for (int i = 0; i + Config.NODE_BYTES_LEN < nodesBytes.length; i += Config.NODE_BYTES_LEN) {
                    //byte[26] 转 Node
                    Node node = new Node(ArrayUtils.subarray(nodesBytes, i, i + Config.NODE_BYTES_LEN));
                    //加入路由表
                    routingTable.put(node);
                    nodeList.add(node);
                }
                //发送该回复的节点,加入路由表
                if(CollectionUtils.isNotEmpty(nodeList))
                    routingTable.put(new Node(id, BTUtil.getIpBySender(sender), sender.getPort(),NodeRankEnum.FIND_NODE_RECEIVE.getCode()));
//                log.info("{}FIND_NODE-RECEIVE.发送者:{},返回节点:{}", LOG, sender,nodeList);

                //入库
//                nodeRepository.save(nodeList);
                break;

            case ANNOUNCE_PEER:
                //如果是请求
                if (messageInfo.getStatus().equals(YEnum.QUERY)) {
                    log.info("{}ANNOUNCE_PEER.map:{}",LOG,map);

                    AnnouncePeer.RequestContent requestContent = new AnnouncePeer.RequestContent(map, sender.getPort());

                    log.info("{}ANNOUNCE_PEER.发送者:{},port:{},info_hash:{}", LOG, sender, requestContent.getPort(), requestContent.getInfo_hash());
                    //入库
                    infoHashRepository.save(new InfoHash(requestContent.getInfo_hash(), InfoHashTypeEnum.ANNOUNCE_PEER.getCode(),
                            BTUtil.getIpBySender(sender) + ":" + requestContent.getPort()));
                    //回复
                    SendUtil.announcePeerReceive(messageInfo.getMessageId(),sender, config.getMain().getNodeId());

                    //加入路由表
                    routingTable.put(new Node(requestContent.getId(),BTUtil.getIpBySender(sender), sender.getPort(),NodeRankEnum.ANNOUNCE_PEER.getCode()));
                    break;
                }
                //如果是回复

                break;

            case GET_PEERS:
                //如果是请求
                if (messageInfo.getStatus().equals(YEnum.QUERY)) {
                    Map<String, Object> aMap = BTUtil.getParamMap(map, "a", "GET_PEERS,找不到a参数.map:" + map);
                    String info_hash = CodeUtil.bytes2HexStr(BTUtil.getParamString(aMap, "info_hash", "GET_PEERS,找不到info_hash参数.map:" + map).getBytes(CharsetUtil.ISO_8859_1));
                    String id1 = CodeUtil.bytes2HexStr(BTUtil.getParamString(aMap, "id", "GET_PEERS,找不到id参数.map:" + map).getBytes(CharsetUtil.ISO_8859_1));
                    List<Node> nodes = routingTable.getForTop8(CodeUtil.hexStr2Bytes(info_hash));
                    log.info("{}GET_PEERS,发送者:{},info_hash:{}", LOG, sender,info_hash);
                    //入库
                    infoHashRepository.save(new InfoHash(info_hash, InfoHashTypeEnum.GET_PEERS.getCode()));
                    //回复时,将自己的nodeId伪造为 和该节点异或值相差不大的值
                    SendUtil.getPeersReceive(messageInfo.getMessageId(),sender, CodeUtil.generateSimilarInfoHashString(info_hash,config.getMain().getSimilarNodeIdNum()),
                            config.getMain().getToken(),nodes);
                    //加入路由表
                    routingTable.put(new Node(id1,BTUtil.getIpBySender(sender), sender.getPort(),NodeRankEnum.GET_PEERS.getCode()));
                    break;
                }
                //如果是回复
                break;
        }


    }

    /**
     * ByteBuf -> byte[]
     */
    private byte[] getBytes(DatagramPacket packet) {
        //读取消息到byte[]
        ByteBuf byteBuf = packet.content();
        byte[] bytes = new byte[byteBuf.readableBytes()];
        byteBuf.readBytes(bytes);
        return bytes;
    }

    /**
     * 异常捕获
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("{}发生异常:{}", LOG, cause.getMessage(), cause);
        //这个巨坑..发生异常(包括我自己抛出来的)后,就关闭了连接,..
//        ctx.close();
    }
}