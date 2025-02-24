package com.angcyo.dsladapter

import android.os.Handler
import android.os.Looper
import androidx.recyclerview.widget.DiffUtil
import com.angcyo.dsladapter.DslDataFilter.Companion.DEFAULT_SHAKE_DELAY
import com.angcyo.dsladapter.filter.*
import com.angcyo.dsladapter.internal.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 *
 * Email:angcyo@126.com
 * @author angcyo
 * @date 2019/05/09
 * Copyright (c) 2019 ShenZhen O&M Cloud Co., Ltd. All rights reserved.
 */

open class DslDataFilter(val dslAdapter: DslAdapter) {

    companion object {

        /**默认抖动检查时长, 毫秒. 如果多次连续调用时长小于此时间, 则跳过处理*/
        var DEFAULT_SHAKE_DELAY = 6L

        /**调试信息开关*/
        var LOG = BuildConfig.DEBUG

        //异步调度器
        private val asyncExecutor: ExecutorService by lazy {
            Executors.newCachedThreadPool()
        }
    }

    /**
     * 过滤后的数据源, 缓存过滤后的数据源, 防止每次都计算.
     *
     * 当有原始数据源发生改变时, 需要调用 [updateFilterItems] 更新过滤后的数据源
     * */
    val filterDataList: MutableList<DslAdapterItem> = mutableListOf()

    val _dispatchUpdatesSet = mutableSetOf<OnDispatchUpdatesListener>()

    /**
     * 可以拦截参与计算[diff]的数据源
     * @param oldDataList 界面显示的数据源
     * @param newDataList 即将显示的数据源
     * @return 需要显示的数据源
     * */
    var onDataFilterAfter: (oldDataList: List<DslAdapterItem>, newDataList: List<DslAdapterItem>) -> List<DslAdapterItem> =
        { _, newDataList -> newDataList }

    /**Diff计算后的数据拦截处理*/
    val dataAfterInterceptorList: MutableList<IFilterAfterInterceptor> =
        mutableListOf(AdapterStatusFilterAfterInterceptor())

    /**前置过滤器*/
    val beforeFilterInterceptorList: MutableList<IFilterInterceptor> =
        mutableListOf()

    /**中置过滤拦截器*/
    val filterInterceptorList: MutableList<IFilterInterceptor> = mutableListOf(
        GroupItemFilterInterceptor(),
        SubItemFilterInterceptor(),
        HideItemFilterInterceptor()
    )

    //更新操作
    private var _updateTaskLit: MutableList<UpdateTaskRunnable> = mutableListOf()

    /**后置过滤器*/
    val afterFilterInterceptorList: MutableList<IFilterInterceptor> =
        mutableListOf()

    private val mainHandler: Handler by lazy {
        Handler(Looper.getMainLooper())
    }

    /**更新过滤后的数据源, 采用的是[DiffUtil]*/
    open fun updateFilterItemDepend(params: FilterParams) {
        val nowTime = System.currentTimeMillis()

        clearTask()

        var filterParams = params

        if (params.justFilter) {
            filterParams = params.copy(justRun = true, asyncDiff = false)
        }

        val taskRunnable = UpdateTaskRunnable()
        taskRunnable._params = filterParams
        taskRunnable._taskStartTime = nowTime

        if (params.justRun) {
            taskRunnable.run()
        } else {
            mainHandler.postDelayed(taskRunnable, params.shakeDelay)
        }
    }

    /**取消所有任务*/
    fun clearTask() {
        _updateTaskLit.forEach {
            it.taskCancel.set(true)
        }
        _updateTaskLit.clear()
    }

    /**Diff之后的数据过滤*/
    open fun filterAfterItemList(
        originList: List<DslAdapterItem>,
        requestList: List<DslAdapterItem>
    ): List<DslAdapterItem> {

        var result: List<DslAdapterItem> = ArrayList(requestList)
        val chain = FilterAfterChain(dslAdapter, this, originList, requestList, false)

        var interruptChain = false

        fun proceed(interceptorList: List<IFilterAfterInterceptor>) {
            if (!interruptChain) {
                for (filter in interceptorList) {
                    if (filter.isEnable) {
                        result = filter.intercept(chain)
                        chain.requestList = result
                        if (chain.interruptChain) {
                            interruptChain = true
                            break
                        }
                    }
                }
            }
        }

        proceed(dataAfterInterceptorList)

        return onDataFilterAfter(originList, result)
    }

    /**过滤[originList]数据源*/
    open fun filterItemList(originList: List<DslAdapterItem>): List<DslAdapterItem> {
        var result: List<DslAdapterItem> = ArrayList(originList)
        val chain = FilterChain(
            dslAdapter,
            this,
            _updateTaskLit.lastOrNull()?._params ?: FilterParams(),
            originList,
            originList,
            false
        )

        var interruptChain = false

        fun proceed(interceptorList: List<IFilterInterceptor>) {
            if (!interruptChain) {
                for (filter in interceptorList) {
                    if (filter.isEnable) {
                        result = filter.intercept(chain)
                        chain.requestList = result
                        if (chain.interruptChain) {
                            interruptChain = true
                            break
                        }
                    }
                }
            }
        }

        //beforeFilterInterceptorList默认就有
        /**[AdapterStatusFilterInterceptor]*/
        proceed(beforeFilterInterceptorList)
        /** [GroupItemFilterInterceptor],[SubItemFilterInterceptor],[HideItemFilterInterceptor] */
        proceed(filterInterceptorList)
        /** [LoadMoreFilterInterceptor] */
        proceed(afterFilterInterceptorList)

        return result
    }

    fun addDispatchUpdatesListener(listener: OnDispatchUpdatesListener) {
        _dispatchUpdatesSet.add(listener)
    }

    fun removeDispatchUpdatesListener(listener: OnDispatchUpdatesListener) {
        _dispatchUpdatesSet.remove(listener)
    }

    /**添加一个数据过滤器*/
    fun addFilterInterceptor(intercept: (chain: FilterChain) -> List<DslAdapterItem>): IFilterInterceptor {
        val obj = object : BaseFilterInterceptor() {
            override fun intercept(chain: FilterChain): List<DslAdapterItem> {
                return intercept(chain)
            }
        }
        filterInterceptorList.add(obj)
        return obj
    }

    /**Diff更新任务*/
    internal inner class UpdateTaskRunnable : Runnable {

        var _params: FilterParams? = null

        /**取消任务执行*/
        val taskCancel: AtomicBoolean = AtomicBoolean(false)

        var _taskStartTime = 0L

        val taskIsCancel: Boolean
            get() = taskCancel.get() || _params?.skip == true

        override fun run() {
            if (taskIsCancel) {
                return
            }

            _params?.apply {
                when {
                    //异步执行diff
                    asyncDiff -> asyncExecutor.submit {
                        doInner()
                    }
                    //立即执行
                    justRun -> {
                        //Cannot call this method while RecyclerView is computing a layout or scrolling
                        if (dslAdapter._recyclerView?.isComputingLayout == true) {
                            mainHandler.post {
                                run()
                            }
                        } else {
                            doInner()
                        }
                    }
                    //post, 抖动过滤
                    else -> mainHandler.post {
                        doInner()
                    }
                }
            }.elseNull {
                taskCancel.set(true)
            }
        }

        private fun doInner() {
            if (taskIsCancel) {
                return
            }

            val resultList = mutableListOf<DslAdapterItem>()

            val startTime = nowTime()
            val logBuilder = StringBuilder()
            if (LOG) logBuilder.append("${dslAdapter.hash()}:${hash()} 开始计算Diff:$startTime")
            val diffResult = calculateDiff(resultList, logBuilder) //diff
            val nowTime = nowTime()
            val dt = nowTime - startTime
            val s = dt / 1000
            //val ms = ((nowTime - startTime) % 1000) * 1f / 1000
            val ms = dt % 1000
            //L.v("${hash()} Diff计算耗时:${String.format("%.3f", s + ms)}s")
            if (LOG) {
                logBuilder.append(" Diff计算耗时:${s}s${ms}ms")
                if (dt > 20) {
                    L.v(logBuilder)
                }
            }

            val oldSize = filterDataList.size
            //因为是异步操作, 所以在延迟前, 就要覆盖 filterDataList 数据源
            resultList.let {
                filterDataList.clear()
                filterDataList.addAll(it)
            }

            //回调到主线程
            val notifyDelay = _params?.notifyDiffDelay ?: -1
            when {
                //延迟通知
                notifyDelay >= 0 -> mainHandler.postDelayed({
                    onDiffResult(diffResult, resultList, oldSize)
                }, notifyDelay)
                //主线程通知
                Looper.getMainLooper() == Looper.myLooper() -> onDiffResult(
                    diffResult,
                    resultList,
                    oldSize
                )
                //主线程通知
                else -> mainHandler.post {
                    onDiffResult(diffResult, resultList, oldSize)
                }
            }
        }

        /**计算[Diff]*/
        private fun calculateDiff(
            resultList: MutableList<DslAdapterItem>,
            logBuilder: StringBuilder
        ): DiffUtil.DiffResult {
            //2个数据源
            val oldList = ArrayList(filterDataList)
            val newList = filterItemList(dslAdapter.adapterItems)

            //异步操作, 先保存数据源
            val _newList = filterAfterItemList(oldList, newList)

            resultList.addAll(_newList)

            if (LOG) logBuilder.append(" 数据列表->原:${oldList.size} 后:${newList.size} 终:${_newList.size}")

            //开始计算diff
            val diffResult = DiffUtil.calculateDiff(
                RDiffCallback(oldList, _newList, object : RItemDiffCallback<DslAdapterItem> {

                    override fun areItemsTheSame(
                        oldData: DslAdapterItem,
                        newData: DslAdapterItem,
                        oldItemPosition: Int, newItemPosition: Int
                    ): Boolean {
                        return oldData.thisAreItemsTheSame(
                            _params?.fromDslAdapterItem,
                            newData,
                            oldItemPosition, newItemPosition
                        )
                    }

                    override fun areContentsTheSame(
                        oldData: DslAdapterItem,
                        newData: DslAdapterItem,
                        oldItemPosition: Int, newItemPosition: Int
                    ): Boolean {
                        return oldData.thisAreContentsTheSame(
                            _params?.fromDslAdapterItem,
                            newData,
                            oldItemPosition, newItemPosition
                        )
                    }

                    override fun getChangePayload(
                        oldData: DslAdapterItem,
                        newData: DslAdapterItem,
                        oldItemPosition: Int, newItemPosition: Int
                    ): Any? {
                        return oldData.thisGetChangePayload(
                            _params?.fromDslAdapterItem,
                            _params?.payload,
                            newData,
                            oldItemPosition, newItemPosition
                        )
                    }
                })
            )

            return diffResult
        }

        /**Diff返回后, 通知界面更新*/
        private fun onDiffResult(
            diffResult: DiffUtil.DiffResult,
            diffList: MutableList<DslAdapterItem>,
            oldSize: Int
        ) {
            if (taskIsCancel) {
                return
            }

            val newSize = diffList.size

            diffList.forEach {
                it.diffResult(_params, diffResult)
            }

            val updateDependItemList = getUpdateDependItemList()

            //是否调用了[Dispatch]
            var isDispatchUpdatesTo = false

            val updatesSet = mutableSetOf<OnDispatchUpdatesListener>()
            updatesSet.addAll(_dispatchUpdatesSet)

            if (_params?.justFilter == true) {
                //仅过滤数据源,不更新界面
                //跳过 dispatchUpdatesTo
            } else {
                //根据diff, 更新adapter
                if (updateDependItemList.isEmpty() &&
                    _params?.updateDependItemWithEmpty == false &&
                    oldSize == newSize
                ) {
                    //跳过[dispatchUpdatesTo]刷新界面, 但是要更新自己
                    dslAdapter.notifyItemChanged(
                        _params?.fromDslAdapterItem,
                        _params?.payload,
                        true
                    )
                } else {

                    //回调
                    updatesSet.forEach {
                        it.onDispatchUpdatesBefore(dslAdapter)
                    }

                    //派发更新界面
                    val updateTo = _params?.onDispatchUpdatesTo
                    if (updateTo == null) {
                        //Cannot call this method while RecyclerView is computing a layout or scrolling
                        if (dslAdapter._recyclerView?.isComputingLayout == true) {
                            //L.w("跳过操作! [RecyclerView]正在计算布局, 请不要在RecyclerView正在布局时, 更新Item. ")
                            dslAdapter._recyclerView?.post {
                                diffResult.dispatchUpdatesTo(RBatchingListUpdateCallback(dslAdapter))
                            }
                            return
                        } else {
                            diffResult.dispatchUpdatesTo(RBatchingListUpdateCallback(dslAdapter))
                        }
                    } else {
                        updateTo(diffResult, diffList)
                    }

                    isDispatchUpdatesTo = true
                }
            }

            notifyUpdateDependItem(updateDependItemList)

            //DispatchUpdates结束回调通知
            if (isDispatchUpdatesTo && updatesSet.isNotEmpty()) {
                updatesSet.forEach {
                    it.onDispatchUpdatesAfter(dslAdapter)
                }
            }

            //任务结束
            val nowTime = System.currentTimeMillis()
            if (LOG) {
                if (nowTime - _taskStartTime > 20) {
                    L.d(
                        "${dslAdapter.hash()}:${hash()} 界面更新结束, 总耗时${
                            LTime.time(_taskStartTime, nowTime)
                        }"
                    )
                }
            }
            _updateTaskLit.remove(this)
            taskCancel.set(true)
        }

        private fun getUpdateDependItemList(): List<DslAdapterItem> {
            //需要通知更新的子项
            val notifyChildFormItemList = mutableListOf<DslAdapterItem>()

            _params?.fromDslAdapterItem?.let { fromItem ->
                notifyChildFormItemList.addAll(dslAdapter.getUpdateDependItemListFrom(fromItem))
            }

            return notifyChildFormItemList
        }

        /**通知依赖的子项, 更新界面*/
        private fun notifyUpdateDependItem(dependItemList: List<DslAdapterItem>) {
            if (_params?.fromDslAdapterItem == null || taskCancel.get()) {
                return
            }

            val fromItem = _params!!.fromDslAdapterItem!!

            if (dependItemList.isNotEmpty()) {
                if (LOG) L.v("来自:${fromItem.simpleHash()} tag:${fromItem.itemTag}的更新↓")
            }

            dependItemList.forEachIndexed { index, dependItem ->
                dependItem.apply {
                    if (itemUpdateFrom(fromItem)) {
                        dependItem.updateAdapterItem(true)
                    }
                    itemUpdateFromListenerList.forEach { it(fromItem) }
                }
                if (LOG) L.v("$index->通知更新:${dependItem.simpleHash()} tag:${dependItem.itemTag}")
            }
        }

        //仅仅只是通知更新被依赖的子项关系
        fun notifyUpdateDependItem() {
            //回调到主线程
            if (Looper.getMainLooper() == Looper.myLooper()) {
                notifyUpdateDependItem(getUpdateDependItemList())
            } else {
                mainHandler.post {
                    notifyUpdateDependItem(getUpdateDependItemList())
                }
            }
        }
    }
}

data class FilterParams(
    /** 触发更新的来源, 定向更新其子项. */
    val fromDslAdapterItem: DslAdapterItem? = null,

    /** 是否跳过当前的Filter操作 */
    var skip: Boolean = false,

    /** 异步计算Diff */
    var asyncDiff: Boolean = false,

    /** 立即执行, 不检查抖动 */
    var justRun: Boolean = true,

    /** 只过滤列表数据, 不通知界面操作, 但是会通过子项更新. 开启此属性会默认设置:[asyncDiff=false] [justRun=true] */
    var justFilter: Boolean = false,

    /** 前提, Diff 之后, 2个数据列表的大小要一致.
     * 当依赖的[DslAdapterItem] [isItemInUpdateList]列表为空时, 是否要调用[dispatchUpdatesTo]更新界面
     * */
    var updateDependItemWithEmpty: Boolean = true,

    /**局部更新标识参数*/
    var payload: Any? = null,

    /**自定义的扩展数据传递, 自行处理*/
    var filterData: Any? = null,

    /**抖动检查延迟时长*/
    var shakeDelay: Long = DEFAULT_SHAKE_DELAY,

    /**计算完diff之后, 延迟多久通知界面*/
    var notifyDiffDelay: Long = -1,

    /**实现此方法, 拦截库中的[dispatchUpdatesTo]界面更新*/
    var onDispatchUpdatesTo: ((DiffUtil.DiffResult, List<DslAdapterItem>) -> Unit)? = null
)

typealias DispatchUpdates = (dslAdapter: DslAdapter) -> Unit

interface OnDispatchUpdatesListener {

    /**
     * 触发[dispatchUpdatesTo]前回调
     * */
    fun onDispatchUpdatesBefore(dslAdapter: DslAdapter) {

    }

    /**
     * 当触发了[dispatchUpdatesTo]后回调
     * */
    fun onDispatchUpdatesAfter(dslAdapter: DslAdapter) {

    }
}