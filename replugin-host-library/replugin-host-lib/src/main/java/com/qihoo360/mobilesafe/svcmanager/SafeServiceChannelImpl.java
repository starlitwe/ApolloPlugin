package com.qihoo360.mobilesafe.svcmanager;

import android.database.MatrixCursor;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import com.qihoo360.mobilesafe.core.BuildConfig;
import com.qihoo360.replugin.IBinderGetter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 安全的Service管理实现，增强线程安全、内存泄漏防护和异常处理
 *
 * @author Boy_Shun
 */
/* PACKAGE */class SafeServiceChannelImpl {

    private static final boolean DEBUG = BuildConfig.DEBUG;
    private static final String TAG = DEBUG ? "SafeServiceChannelImpl" : SafeServiceChannelImpl.class.getSimpleName();
    
    // 服务名称最大长度限制
    private static final int MAX_SERVICE_NAME_LENGTH = 256;
    // 最大服务数量限制
    private static final int MAX_SERVICES_COUNT = 1000;

    // 使用ConcurrentHashMap保证线程安全
    private static final ConcurrentHashMap<String, IBinder> sServices = new ConcurrentHashMap<>(32);
    private static final ConcurrentHashMap<String, IBinderGetter> sDelayedServices = new ConcurrentHashMap<>(32);
    
    // 服务引用计数器，用于跟踪服务使用情况
    private static final ConcurrentHashMap<String, AtomicInteger> sServiceRefCounters = new ConcurrentHashMap<>();
    
    // 服务统计信息
    private static final AtomicInteger sTotalServices = new AtomicInteger(0);

    /**
     * 验证服务名称的合法性
     */
    private static boolean isValidServiceName(String serviceName) {
        if (TextUtils.isEmpty(serviceName)) {
            return false;
        }
        
        if (serviceName.length() > MAX_SERVICE_NAME_LENGTH) {
            Log.w(TAG, "Service name too long: " + serviceName.length());
            return false;
        }
        
        // 检查服务名称格式（只允许字母、数字、下划线和点号）
        if (!serviceName.matches("^[a-zA-Z0-9._-]+$")) {
            Log.w(TAG, "Invalid service name format: " + serviceName);
            return false;
        }
        
        return true;
    }

    /**
     * 检查服务数量限制
     */
    private static boolean checkServiceLimit() {
        return sTotalServices.get() < MAX_SERVICES_COUNT;
    }

    /**
     * 安全的ServiceChannel实现
     */
    static IServiceChannel.Stub sServiceChannelImpl = new IServiceChannel.Stub() {

        @Override
        public IBinder getService(String serviceName) throws RemoteException {
            long startTime = DEBUG ? System.currentTimeMillis() : 0;
            
            try {
                if (DEBUG) {
                    Log.d(TAG, "[getService] --> serviceName = " + serviceName);
                }

                if (!isValidServiceName(serviceName)) {
                    Log.e(TAG, "Invalid service name in getService: " + serviceName);
                    throw new IllegalArgumentException("Invalid service name");
                }

                // 首先尝试从常规服务Map中获取
                IBinder service = sServices.get(serviceName);

                // 如果常规服务不存在，尝试从延迟服务Map中获取
                if (service == null) {
                    service = fetchFromDelayedMap(serviceName);
                }

                // 检查服务是否可用
                if (service != null && !isBinderAliveSafe(service)) {
                    if (DEBUG) {
                        Log.d(TAG, "[getService] --> service died: " + serviceName);
                    }
                    removeServiceInternal(serviceName);
                    return null;
                }

                // 更新引用计数
                if (service != null) {
                    updateReferenceCount(serviceName, true);
                }

                return service;
                
            } catch (Exception e) {
                Log.e(TAG, "Error in getService: " + serviceName, e);
                throw new RemoteException("getService failed: " + e.getMessage());
            } finally {
                if (DEBUG) {
                    long duration = System.currentTimeMillis() - startTime;
                    Log.d(TAG, "[getService] <-- serviceName = " + serviceName + ", duration = " + duration + "ms");
                }
            }
        }

        /**
         * 安全地检查Binder是否存活
         */
        private boolean isBinderAliveSafe(IBinder binder) {
            try {
                return binder.isBinderAlive() && binder.pingBinder();
            } catch (Exception e) {
                Log.w(TAG, "Error checking binder alive status", e);
                return false;
            }
        }

        /**
         * 从延迟服务Map中获取服务
         */
        private IBinder fetchFromDelayedMap(String serviceName) {
            IBinderGetter getter = sDelayedServices.get(serviceName);
            if (getter == null) {
                return null;
            }

            try {
                IBinder service = getter.get();
                if (service != null) {
                    // 将延迟服务转换为常规服务
                    addServiceInternal(serviceName, service);
                    sDelayedServices.remove(serviceName);
                    return service;
                }
            } catch (DeadObjectException e) {
                if (DEBUG) {
                    Log.w(TAG, "DeadObjectException in fetchFromDelayedMap: " + serviceName, e);
                }
                // 远端进程已经挂掉，移除延迟服务
                sDelayedServices.remove(serviceName);
            } catch (RemoteException e) {
                if (DEBUG) {
                    Log.w(TAG, "RemoteException in fetchFromDelayedMap: " + serviceName, e);
                }
                // 对于其他RemoteException，暂时保留延迟服务，可能只是临时错误
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error in fetchFromDelayedMap: " + serviceName, e);
                sDelayedServices.remove(serviceName);
            }
            
            return null;
        }

        @Override
        public void addService(String serviceName, IBinder service) throws RemoteException {
            if (!isValidServiceName(serviceName)) {
                Log.e(TAG, "Invalid service name in addService: " + serviceName);
                throw new IllegalArgumentException("Invalid service name");
            }
            
            if (service == null) {
                Log.e(TAG, "Null service in addService: " + serviceName);
                throw new IllegalArgumentException("Service cannot be null");
            }
            
            addServiceInternal(serviceName, service);
        }

        /**
         * 内部添加服务实现
         */
        private void addServiceInternal(String serviceName, IBinder service) {
            if (!checkServiceLimit()) {
                Log.e(TAG, "Service limit reached, cannot add: " + serviceName);
                throw new IllegalStateException("Service limit reached");
            }
            
            IBinder previous = sServices.put(serviceName, service);
            if (previous == null) {
                sTotalServices.incrementAndGet();
            }
            
            // 初始化引用计数
            sServiceRefCounters.putIfAbsent(serviceName, new AtomicInteger(0));
            
            if (DEBUG) {
                Log.d(TAG, "[addService] --> serviceName = " + serviceName + 
                      ", totalServices = " + sTotalServices.get());
            }
        }

        @Override
        public void addServiceDelayed(String serviceName, IBinderGetter getter) throws RemoteException {
            if (!isValidServiceName(serviceName)) {
                Log.e(TAG, "Invalid service name in addServiceDelayed: " + serviceName);
                throw new IllegalArgumentException("Invalid service name");
            }
            
            if (getter == null) {
                Log.e(TAG, "Null getter in addServiceDelayed: " + serviceName);
                throw new IllegalArgumentException("Getter cannot be null");
            }
            
            if (!checkServiceLimit()) {
                Log.e(TAG, "Service limit reached, cannot add delayed: " + serviceName);
                throw new IllegalStateException("Service limit reached");
            }
            
            sDelayedServices.put(serviceName, getter);
            sServiceRefCounters.putIfAbsent(serviceName, new AtomicInteger(0));
            
            if (DEBUG) {
                Log.d(TAG, "[addServiceDelayed] --> serviceName = " + serviceName);
            }
        }

        @Override
        public void removeService(String serviceName) throws RemoteException {
            if (!isValidServiceName(serviceName)) {
                Log.e(TAG, "Invalid service name in removeService: " + serviceName);
                return;
            }
            
            removeServiceInternal(serviceName);
        }

        /**
         * 内部移除服务实现
         */
        private void removeServiceInternal(String serviceName) {
            IBinder removed = sServices.remove(serviceName);
            sDelayedServices.remove(serviceName);
            sServiceRefCounters.remove(serviceName);
            
            if (removed != null) {
                sTotalServices.decrementAndGet();
            }
            
            if (DEBUG) {
                Log.d(TAG, "[removeService] --> serviceName = " + serviceName + 
                      ", totalServices = " + sTotalServices.get());
            }
        }

        /**
         * 更新服务引用计数
         */
        private void updateReferenceCount(String serviceName, boolean increment) {
            AtomicInteger counter = sServiceRefCounters.get(serviceName);
            if (counter != null) {
                if (increment) {
                    counter.incrementAndGet();
                } else {
                    counter.decrementAndGet();
                }
                
                if (DEBUG) {
                    Log.d(TAG, "[updateReferenceCount] serviceName = " + serviceName + 
                          ", count = " + counter.get());
                }
            }
        }

        @Override
        public IBinder getPluginService(String pluginName, String serviceName, IBinder deathMonitor) throws RemoteException {
            try {
                if (!isValidServiceName(pluginName) || !isValidServiceName(serviceName)) {
                    Log.e(TAG, "Invalid plugin or service name: " + pluginName + "/" + serviceName);
                    return null;
                }
                
                return PluginServiceManager.getPluginService(pluginName, serviceName, getCallingPid(), deathMonitor);
            } catch (Exception e) {
                Log.e(TAG, "Error in getPluginService: " + pluginName + "/" + serviceName, e);
                throw new RemoteException("getPluginService failed: " + e.getMessage());
            }
        }

        @Override
        public void onPluginServiceRefReleased(String pluginName, String serviceName) throws RemoteException {
            try {
                if (!isValidServiceName(pluginName) || !isValidServiceName(serviceName)) {
                    Log.e(TAG, "Invalid plugin or service name in onPluginServiceRefReleased: " + pluginName + "/" + serviceName);
                    return;
                }
                
                PluginServiceManager.onRefReleased(pluginName, serviceName, getCallingPid());
            } catch (Exception e) {
                Log.e(TAG, "Error in onPluginServiceRefReleased: " + pluginName + "/" + serviceName, e);
                throw new RemoteException("onPluginServiceRefReleased failed: " + e.getMessage());
            }
        }
    };

    /**
     * 清理所有服务（用于进程退出时）
     */
    public static void cleanup() {
        sServices.clear();
        sDelayedServices.clear();
        sServiceRefCounters.clear();
        sTotalServices.set(0);
        
        if (DEBUG) {
            Log.d(TAG, "All services cleaned up");
        }
    }

    /**
     * 获取服务统计信息（用于监控和调试）
     */
    public static String getStatistics() {
        return String.format("Services: %d regular, %d delayed, %d total", 
                sServices.size(), sDelayedServices.size(), sTotalServices.get());
    }

    /**
     * 检查服务是否存在
     */
    public static boolean containsService(String serviceName) {
        return sServices.containsKey(serviceName) || sDelayedServices.containsKey(serviceName);
    }

    static MatrixCursor sServiceChannelCursor = ServiceChannelCursor.makeCursor(sServiceChannelImpl);
}