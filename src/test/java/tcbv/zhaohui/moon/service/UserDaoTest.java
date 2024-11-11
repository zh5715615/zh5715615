package tcbv.zhaohui.moon.service;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.CollectionUtils;
import tcbv.zhaohui.moon.config.MoonConstant;
import tcbv.zhaohui.moon.config.Web3Config;
import tcbv.zhaohui.moon.dao.TbGameResultDao;
import tcbv.zhaohui.moon.dao.TbRewardRecordDao;
import tcbv.zhaohui.moon.dao.TbTxRecordDao;
import tcbv.zhaohui.moon.dao.TbUserDao;
import tcbv.zhaohui.moon.entity.TbGameResult;
import tcbv.zhaohui.moon.entity.TbRewardRecord;
import tcbv.zhaohui.moon.entity.TbTxRecord;
import tcbv.zhaohui.moon.entity.TbUser;
import tcbv.zhaohui.moon.utils.CustomizeTimeUtil;
import tcbv.zhaohui.moon.utils.EthMathUtil;
import tcbv.zhaohui.moon.utils.GsonUtil;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@SpringBootTest
@Slf4j
public class UserDaoTest {
    @Resource
    private TbUserDao userDao;

    @Resource
    private IMoonBaseService moonBaseService;

    @Resource
    private TbTxRecordDao txRecordDao;
    @Resource
    private TbRewardRecordDao rewardRecordDao;

    @Resource
    private TbGameResultDao gameResultDao;

    @Autowired
    private IEthereumService ethereumService;

    @Autowired
    private Web3Config web3Config;

    @BeforeEach
    public void before() {
        ethereumService.init();
        moonBaseService.init(web3Config.getMoonBaseAddress());
    }

    @Test
    public void testQueryById() {
        TbUser user = userDao.queryById("016587eb-727b-4533-af8f-cc9950e2e9ad");
        System.out.println(GsonUtil.toJson(user));
    }

    private Integer getGameType() {
        return MoonConstant.DICE_ROLLER_GAME;
    }

    private void allocReward(int winner, int turns) {
        int loser;
        if (getGameType().equals(MoonConstant.DICE_ROLLER_GAME)) {
            if (winner == MoonConstant.DICE_ROLLER_SINGLE) {
                loser = MoonConstant.DICE_ROLLER_DOUBLE;
            } else if (winner == MoonConstant.DICE_ROLLER_DOUBLE) {
                loser = MoonConstant.DICE_ROLLER_SINGLE;
            } else {
                log.error("开奖异常，没有胜方败方");
                return;
            }
        } else if (getGameType().equals(MoonConstant.GUESS_BNB_PRICE_GAME)) {
            if (winner == MoonConstant.GUESS_BNB_RISE) {
                loser = MoonConstant.GUESS_BNB_FALL;
            } else if (winner == MoonConstant.GUESS_BNB_FALL) {
                loser = MoonConstant.GUESS_BNB_RISE;
            } else {
                log.error("开奖异常，没有胜方败方");
                return;
            }
        } else {
            log.error("游戏类型错误");
            return;
        }

        double loserAmount = txRecordDao.betNumber(getGameType(), turns, loser);
        double winnerAmount = txRecordDao.betNumber(getGameType(), turns, winner);
        double poolTotalAmount = loserAmount + winnerAmount;
        List<TbTxRecord> list = txRecordDao.winnerList(getGameType(), turns, winner);
        log.info("list is {}", GsonUtil.toJson(list));
        if (CollectionUtils.isEmpty(list)) {
            List<TbTxRecord> loserList = txRecordDao.loserList(getGameType(), turns, loser);
            log.info("loserList is {}", GsonUtil.toJson(loserList));
            if (!CollectionUtils.isEmpty(loserList)) {
                List<TbRewardRecord> loserRecordList = new ArrayList<>();
                for (TbTxRecord txRecord : loserList) {
                    TbRewardRecord loserRecord = new TbRewardRecord();
                    loserRecord.setId(UUID.randomUUID().toString());
                    loserRecord.setTurns(txRecord.getTurns());
                    loserRecord.setUserId(txRecord.getUserId());
                    loserRecord.setRewardAmount(txRecord.getAmount().negate());
                    loserRecord.setGameType(txRecord.getGameType() + "");
                    loserRecord.setCreateTime(CustomizeTimeUtil.formatTimestamp(System.currentTimeMillis()));
                    loserRecordList.add(loserRecord);
                }
                rewardRecordDao.insertBatch(loserRecordList);
            }
            return;
        }

        List<BigInteger> rewardAmountList = new ArrayList<>();
        List<BigInteger> totalAmountList = new ArrayList<>();
        List<String> userAddressList = new ArrayList<>();
        BigDecimal totalAmount = list.stream().map(TbTxRecord::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<TbRewardRecord> rewardRecordList = new ArrayList<>();
        for (TbTxRecord tbTxRecord : list) {
            double rate = tbTxRecord.getAmount().doubleValue() / totalAmount.doubleValue();
            log.info("loserAmount is {}, rate is {}", loserAmount, rate);
            BigDecimal reward = BigDecimal.valueOf(loserAmount * rate);
            BigDecimal totalReward = BigDecimal.valueOf(poolTotalAmount * rate);
            log.info("reward is {}", reward.doubleValue());
            log.info("tbTxRecord is {}", GsonUtil.toJson(tbTxRecord));
            TbUser user = userDao.queryById(tbTxRecord.getUserId());
            log.info("user is {}", user == null ? null : GsonUtil.toJson(user));
            rewardAmountList.add(reward.toBigInteger());
            totalAmountList.add(totalReward.toBigInteger().multiply(BigInteger.TEN.pow(6)));
            userAddressList.add(user.getAddress());

            TbRewardRecord rewardRecord = new TbRewardRecord();
            rewardRecord.setId(UUID.randomUUID().toString());
            rewardRecord.setTurns(tbTxRecord.getTurns());
            rewardRecord.setUserId(tbTxRecord.getUserId());
            rewardRecord.setRewardAmount(EthMathUtil.bigIntegerToBigDecimal(reward.toBigInteger(), 0));
            rewardRecord.setGameType(tbTxRecord.getGameType() + "");
            rewardRecord.setCreateTime(CustomizeTimeUtil.formatTimestamp(System.currentTimeMillis()));
            rewardRecordList.add(rewardRecord);
        }
        try {
            String txHash = moonBaseService.allocReward(userAddressList, totalAmountList);
            log.info("中奖发放奖励hash值为{}", txHash);
            for (TbRewardRecord rewardRecord : rewardRecordList) {
                rewardRecord.setTxHash(txHash);
            }
        } catch (Exception e) {
            log.error("上链异常：", e);
        } finally {
            rewardRecordDao.insertBatch(rewardRecordList);
        }
    }

    @Test
    public void test1() {
        int winner = 2;
        int turns = 496;
        allocReward(winner, turns);
    }

    @Test
    public void test2() {
        Integer gameTurns = 280;
        TbGameResult gameResult = gameResultDao.findGameTypeAndTurnsInfo(gameTurns, getGameType());
        if (gameResult == null) {
            return;
        }
        List<TbTxRecord> txRecordList = txRecordDao.findTurnsGameInfo(gameTurns,getGameType());
        List<String> txHashList = txRecordList.stream().map(TbTxRecord::getTxHash).collect(Collectors.toList());
        int result = 0;
        if (!CollectionUtils.isEmpty(txHashList)) {
            BigInteger sum = BigInteger.ZERO;
            for (String s : txHashList) {
                BigInteger b = new BigInteger(s.substring(2), 16);
                sum = sum.and(b);
            }
            result = sum.mod(BigInteger.valueOf(16)).add(BigInteger.valueOf(3)).intValue();
        }
        gameResult.setSingleAndDouble(result);
        gameResult.setDrawnTime(CustomizeTimeUtil.formatTimestamp(System.currentTimeMillis()));
        gameResultDao.update(gameResult);

        allocReward(2 - result % 2, gameTurns);
    }
}
