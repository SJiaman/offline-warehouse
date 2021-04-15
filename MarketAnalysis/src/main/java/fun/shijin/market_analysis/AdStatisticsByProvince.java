package fun.shijin.market_analysis;

import fun.shijin.market_analysis.beans.AdClickEvent;
import fun.shijin.market_analysis.beans.AdCountViewByProvince;
import fun.shijin.market_analysis.beans.BlackListUserWarning;
import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.java.tuple.Tuple;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.streaming.api.functions.timestamps.AscendingTimestampExtractor;
import org.apache.flink.streaming.api.functions.windowing.WindowFunction;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

import java.net.URL;
import java.sql.Timestamp;

/**
 * @Author Jiaman
 * @Date 2021/4/15 22:18
 * @Desc
 */

public class AdStatisticsByProvince {
    public static void main(String[] args) throws Exception{
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
        env.setParallelism(1);

        // 1. 从文件中读取数据
        URL resource = AdStatisticsByProvince.class.getResource("/AdClickLog.csv");
        DataStream<AdClickEvent> adClickEventStream = env.readTextFile(resource.getPath())
                .map( line -> {
                    String[] fields = line.split(",");
                    return new AdClickEvent(new Long(fields[0]), new Long(fields[1]), fields[2], fields[3], new Long(fields[4]));
                } )
                .assignTimestampsAndWatermarks(new AscendingTimestampExtractor<AdClickEvent>() {
                    @Override
                    public long extractAscendingTimestamp(AdClickEvent element) {
                        return element.getTimestamp() * 1000L;
                    }
                });

        // 2. 对同一个用户点击同一个广告的行为进行检测报警
        SingleOutputStreamOperator<AdClickEvent> filterAdClickStream = adClickEventStream
                .keyBy("userId", "adId")    // 基于用户id和广告id做分组
                .process(new FilterBlackListUser(100));

        // 3. 基于省份分组，开窗聚合
        SingleOutputStreamOperator<AdCountViewByProvince> adCountResultStream = filterAdClickStream
                .keyBy(AdClickEvent::getProvince)
                .timeWindow(Time.hours(1), Time.minutes(5))     // 定义滑窗，5分钟输出一次
                .aggregate(new AdCountAgg(), new AdCountResult());

        adCountResultStream.print();
        filterAdClickStream.getSideOutput(new OutputTag<BlackListUserWarning>("blacklist"){}).print("blacklist-user");

        env.execute("ad count by province job");
    }

    public static class FilterBlackListUser extends KeyedProcessFunction<Tuple, AdClickEvent, AdClickEvent> {
        // 定义属性，点击次数上限
        private Integer countUpperBound;

        public FilterBlackListUser(Integer countUpperBound) {
            this.countUpperBound = countUpperBound;
        }

        //定义状态，保存当前用户对某一广告的点击次数
        ValueState<Long> countState;

        //定义一个标记状态，保存当前用户是否已经被发送到黑名单
        ValueState<Boolean> isSentState;

        @Override
        public void open(Configuration parameters) throws Exception {
            countState = getRuntimeContext().getState(new ValueStateDescriptor<Long>("ad-count", Long.class, 0L));
            isSentState = getRuntimeContext().getState(new ValueStateDescriptor<Boolean>("is-sent", Boolean.class, false));
        }

        @Override
        public void processElement(AdClickEvent value, Context ctx, Collector<AdClickEvent> out) throws Exception {
            // 判断当前用户对同一个广告的点击次数，如果不够上限，正常输出，达到上限，直接过滤并输出到黑名单报警
            Long curCount = countState.value();

            if (curCount == null) {
                Long ts = (ctx.timerService().currentProcessingTime() / (24*60*60*1000) + 1) * (24*60*60*1000) - 8*60*60*1000;
                ctx.timerService().registerEventTimeTimer(ts);
            }

            // 判断是否报警
            if (curCount >= countUpperBound) {
                if (!isSentState.value()) {
                    isSentState.update(true);
                    ctx.output(new OutputTag<BlackListUserWarning>("blacklist"){},
                            new BlackListUserWarning(value.getUserId(), value.getAdId(), "click over 100 times"));
                }
                return;
            }

            countState.update(curCount + 1);
            out.collect(value);
        }

        @Override
        public void onTimer(long timestamp, OnTimerContext ctx, Collector<AdClickEvent> out) throws Exception {
            countState.clear();
            isSentState.clear();
        }

    }
    public static class AdCountAgg implements AggregateFunction<AdClickEvent, Long, Long>{
        @Override
        public Long createAccumulator() {
            return 0L;
        }

        @Override
        public Long add(AdClickEvent value, Long accumulator) {
            return accumulator + 1;
        }

        @Override
        public Long getResult(Long accumulator) {
            return accumulator;
        }

        @Override
        public Long merge(Long a, Long b) {
            return a + b;
        }
    }

    public static class AdCountResult implements WindowFunction<Long, AdCountViewByProvince, String, TimeWindow> {
        @Override
        public void apply(String province, TimeWindow window, Iterable<Long> input, Collector<AdCountViewByProvince> out) throws Exception {
            String windowEnd = new Timestamp( window.getEnd() ).toString();
            Long count = input.iterator().next();
            out.collect( new AdCountViewByProvince(province, windowEnd, count) );
        }
    }
}