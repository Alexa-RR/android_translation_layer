/*
 * This file is auto-generated.  DO NOT MODIFY.
 */
package android.car;
/** @hide */
public interface ICar extends android.os.IInterface
{
  /** Default implementation for ICar. */
  public static class Default implements android.car.ICar
  {
    /**
         * Helper binder is for ICarServiceHelper. It is for the communication from CarService ->
         * CarServiceHelperService.
         * Receiver binder is type of IResultReceiver. It would set ICarSystemServerClient binder for
         * CarServiceHelperService.
         * DO NOT CHANGE the number.
         */
    @Override public void setSystemServerConnections(android.os.IBinder helper, android.os.IBinder receiver) throws android.os.RemoteException
    {
    }
    // Rest of the calls are used for Apps to CarService communication

    @Override public android.os.IBinder getCarService(java.lang.String serviceName) throws android.os.RemoteException
    {
      return null;
    }
    @Override public int getCarConnectionType() throws android.os.RemoteException
    {
      return 0;
    }
    @Override public boolean isFeatureEnabled(java.lang.String featureName) throws android.os.RemoteException
    {
      return false;
    }
    @Override public int enableFeature(java.lang.String featureName) throws android.os.RemoteException
    {
      return 0;
    }
    @Override public int disableFeature(java.lang.String featureName) throws android.os.RemoteException
    {
      return 0;
    }
    @Override public java.util.List<java.lang.String> getAllEnabledFeatures() throws android.os.RemoteException
    {
      return null;
    }
    @Override public java.util.List<java.lang.String> getAllPendingDisabledFeatures() throws android.os.RemoteException
    {
      return null;
    }
    @Override public java.util.List<java.lang.String> getAllPendingEnabledFeatures() throws android.os.RemoteException
    {
      return null;
    }
    /**
         * Get class name for experimental feature. Class should have constructor taking (Car, IBinder)
         * and should inherit CarManagerBase.
         */
    @Override public java.lang.String getCarManagerClassForFeature(java.lang.String featureName) throws android.os.RemoteException
    {
      return null;
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements android.car.ICar
  {
    private static final java.lang.String DESCRIPTOR = "android.car.ICar";
    /** Construct the stub at attach it to the interface. */
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an android.car.ICar interface,
     * generating a proxy if needed.
     */
    public static android.car.ICar asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof android.car.ICar))) {
        return ((android.car.ICar)iin);
      }
      return new android.car.ICar.Stub.Proxy(obj);
    }
    @Override public android.os.IBinder asBinder()
    {
      return this;
    }
    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      java.lang.String descriptor = DESCRIPTOR;
      switch (code)
      {
        case INTERFACE_TRANSACTION:
        {
          reply.writeString(descriptor);
          return true;
        }
        case TRANSACTION_setSystemServerConnections:
        {
          data.enforceInterface(descriptor);
          android.os.IBinder _arg0;
          _arg0 = data.readStrongBinder();
          android.os.IBinder _arg1;
          _arg1 = data.readStrongBinder();
          this.setSystemServerConnections(_arg0, _arg1);
          return true;
        }
        case TRANSACTION_getCarService:
        {
          data.enforceInterface(descriptor);
          java.lang.String _arg0;
          _arg0 = data.readString();
          android.os.IBinder _result = this.getCarService(_arg0);
          reply.writeNoException();
          reply.writeStrongBinder(_result);
          return true;
        }
        case TRANSACTION_getCarConnectionType:
        {
          data.enforceInterface(descriptor);
          int _result = this.getCarConnectionType();
          reply.writeNoException();
          reply.writeInt(_result);
          return true;
        }
        case TRANSACTION_isFeatureEnabled:
        {
          data.enforceInterface(descriptor);
          java.lang.String _arg0;
          _arg0 = data.readString();
          boolean _result = this.isFeatureEnabled(_arg0);
          reply.writeNoException();
          reply.writeInt(((_result)?(1):(0)));
          return true;
        }
        case TRANSACTION_enableFeature:
        {
          data.enforceInterface(descriptor);
          java.lang.String _arg0;
          _arg0 = data.readString();
          int _result = this.enableFeature(_arg0);
          reply.writeNoException();
          reply.writeInt(_result);
          return true;
        }
        case TRANSACTION_disableFeature:
        {
          data.enforceInterface(descriptor);
          java.lang.String _arg0;
          _arg0 = data.readString();
          int _result = this.disableFeature(_arg0);
          reply.writeNoException();
          reply.writeInt(_result);
          return true;
        }
        case TRANSACTION_getAllEnabledFeatures:
        {
          data.enforceInterface(descriptor);
          java.util.List<java.lang.String> _result = this.getAllEnabledFeatures();
          reply.writeNoException();
          reply.writeStringList(_result);
          return true;
        }
        case TRANSACTION_getAllPendingDisabledFeatures:
        {
          data.enforceInterface(descriptor);
          java.util.List<java.lang.String> _result = this.getAllPendingDisabledFeatures();
          reply.writeNoException();
          reply.writeStringList(_result);
          return true;
        }
        case TRANSACTION_getAllPendingEnabledFeatures:
        {
          data.enforceInterface(descriptor);
          java.util.List<java.lang.String> _result = this.getAllPendingEnabledFeatures();
          reply.writeNoException();
          reply.writeStringList(_result);
          return true;
        }
        case TRANSACTION_getCarManagerClassForFeature:
        {
          data.enforceInterface(descriptor);
          java.lang.String _arg0;
          _arg0 = data.readString();
          java.lang.String _result = this.getCarManagerClassForFeature(_arg0);
          reply.writeNoException();
          reply.writeString(_result);
          return true;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
    }
    private static class Proxy implements android.car.ICar
    {
      private android.os.IBinder mRemote;
      Proxy(android.os.IBinder remote)
      {
        mRemote = remote;
      }
      @Override public android.os.IBinder asBinder()
      {
        return mRemote;
      }
      public java.lang.String getInterfaceDescriptor()
      {
        return DESCRIPTOR;
      }
      /**
           * Helper binder is for ICarServiceHelper. It is for the communication from CarService ->
           * CarServiceHelperService.
           * Receiver binder is type of IResultReceiver. It would set ICarSystemServerClient binder for
           * CarServiceHelperService.
           * DO NOT CHANGE the number.
           */
      @Override public void setSystemServerConnections(android.os.IBinder helper, android.os.IBinder receiver) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder(helper);
          _data.writeStrongBinder(receiver);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setSystemServerConnections, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().setSystemServerConnections(helper, receiver);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      // Rest of the calls are used for Apps to CarService communication

      @Override public android.os.IBinder getCarService(java.lang.String serviceName) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        android.os.IBinder _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(serviceName);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getCarService, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            return getDefaultImpl().getCarService(serviceName);
          }
          _reply.readException();
          _result = _reply.readStrongBinder();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public int getCarConnectionType() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getCarConnectionType, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            return getDefaultImpl().getCarConnectionType();
          }
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public boolean isFeatureEnabled(java.lang.String featureName) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(featureName);
          boolean _status = mRemote.transact(Stub.TRANSACTION_isFeatureEnabled, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            return getDefaultImpl().isFeatureEnabled(featureName);
          }
          _reply.readException();
          _result = (0!=_reply.readInt());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public int enableFeature(java.lang.String featureName) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(featureName);
          boolean _status = mRemote.transact(Stub.TRANSACTION_enableFeature, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            return getDefaultImpl().enableFeature(featureName);
          }
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public int disableFeature(java.lang.String featureName) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(featureName);
          boolean _status = mRemote.transact(Stub.TRANSACTION_disableFeature, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            return getDefaultImpl().disableFeature(featureName);
          }
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public java.util.List<java.lang.String> getAllEnabledFeatures() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.util.List<java.lang.String> _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getAllEnabledFeatures, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            return getDefaultImpl().getAllEnabledFeatures();
          }
          _reply.readException();
          _result = _reply.createStringArrayList();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public java.util.List<java.lang.String> getAllPendingDisabledFeatures() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.util.List<java.lang.String> _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getAllPendingDisabledFeatures, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            return getDefaultImpl().getAllPendingDisabledFeatures();
          }
          _reply.readException();
          _result = _reply.createStringArrayList();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public java.util.List<java.lang.String> getAllPendingEnabledFeatures() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.util.List<java.lang.String> _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getAllPendingEnabledFeatures, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            return getDefaultImpl().getAllPendingEnabledFeatures();
          }
          _reply.readException();
          _result = _reply.createStringArrayList();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      /**
           * Get class name for experimental feature. Class should have constructor taking (Car, IBinder)
           * and should inherit CarManagerBase.
           */
      @Override public java.lang.String getCarManagerClassForFeature(java.lang.String featureName) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.lang.String _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(featureName);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getCarManagerClassForFeature, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            return getDefaultImpl().getCarManagerClassForFeature(featureName);
          }
          _reply.readException();
          _result = _reply.readString();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      public static android.car.ICar sDefaultImpl;
    }
    static final int TRANSACTION_setSystemServerConnections = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_getCarService = (android.os.IBinder.FIRST_CALL_TRANSACTION + 11);
    static final int TRANSACTION_getCarConnectionType = (android.os.IBinder.FIRST_CALL_TRANSACTION + 12);
    static final int TRANSACTION_isFeatureEnabled = (android.os.IBinder.FIRST_CALL_TRANSACTION + 13);
    static final int TRANSACTION_enableFeature = (android.os.IBinder.FIRST_CALL_TRANSACTION + 14);
    static final int TRANSACTION_disableFeature = (android.os.IBinder.FIRST_CALL_TRANSACTION + 15);
    static final int TRANSACTION_getAllEnabledFeatures = (android.os.IBinder.FIRST_CALL_TRANSACTION + 16);
    static final int TRANSACTION_getAllPendingDisabledFeatures = (android.os.IBinder.FIRST_CALL_TRANSACTION + 17);
    static final int TRANSACTION_getAllPendingEnabledFeatures = (android.os.IBinder.FIRST_CALL_TRANSACTION + 18);
    static final int TRANSACTION_getCarManagerClassForFeature = (android.os.IBinder.FIRST_CALL_TRANSACTION + 19);
    public static boolean setDefaultImpl(android.car.ICar impl) {
      // Only one user of this interface can use this function
      // at a time. This is a heuristic to detect if two different
      // users in the same process use this function.
      if (Stub.Proxy.sDefaultImpl != null) {
        throw new IllegalStateException("setDefaultImpl() called twice");
      }
      if (impl != null) {
        Stub.Proxy.sDefaultImpl = impl;
        return true;
      }
      return false;
    }
    public static android.car.ICar getDefaultImpl() {
      return Stub.Proxy.sDefaultImpl;
    }
  }
  /**
       * Helper binder is for ICarServiceHelper. It is for the communication from CarService ->
       * CarServiceHelperService.
       * Receiver binder is type of IResultReceiver. It would set ICarSystemServerClient binder for
       * CarServiceHelperService.
       * DO NOT CHANGE the number.
       */
  public void setSystemServerConnections(android.os.IBinder helper, android.os.IBinder receiver) throws android.os.RemoteException;
  // Rest of the calls are used for Apps to CarService communication

  public android.os.IBinder getCarService(java.lang.String serviceName) throws android.os.RemoteException;
  public int getCarConnectionType() throws android.os.RemoteException;
  public boolean isFeatureEnabled(java.lang.String featureName) throws android.os.RemoteException;
  public int enableFeature(java.lang.String featureName) throws android.os.RemoteException;
  public int disableFeature(java.lang.String featureName) throws android.os.RemoteException;
  public java.util.List<java.lang.String> getAllEnabledFeatures() throws android.os.RemoteException;
  public java.util.List<java.lang.String> getAllPendingDisabledFeatures() throws android.os.RemoteException;
  public java.util.List<java.lang.String> getAllPendingEnabledFeatures() throws android.os.RemoteException;
  /**
       * Get class name for experimental feature. Class should have constructor taking (Car, IBinder)
       * and should inherit CarManagerBase.
       */
  public java.lang.String getCarManagerClassForFeature(java.lang.String featureName) throws android.os.RemoteException;
}
